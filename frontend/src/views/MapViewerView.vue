<template>
  <section class="map-page">
    <header class="map-heading"><div><h2>地图工作台</h2><p>查看分析结果，圈定监测范围。</p></div></header>
    <div class="map-workspace" :class="{ 'panel-closed': !panelOpen }">
      <div class="workspace-toolbar">
        <nav class="work-modes" aria-label="切换地图工作">
          <button v-for="item in modes" :key="item.key" type="button" :class="{ active: panelOpen && mode === item.key }"
            :aria-pressed="panelOpen && mode === item.key" @click="openMode(item.key)"><el-icon><component :is="item.icon" /></el-icon>{{ item.label }}</button>
        </nav>
        <el-popover placement="bottom-end" :width="260" trigger="click">
          <template #reference><el-button :icon="Setting" class="display-button">显示设置</el-button></template>
          <div class="display-settings">
            <h3>地图显示</h3>
            <div class="base-toggle"><label for="map-base-toggle">参考底图</label><el-switch id="map-base-toggle" v-model="baseMapVisible" aria-label="参考底图" @change="setBaseLayerVisible(baseMapVisible)" /></div>
            <p>圈定范围时，请以影像为准。</p>
            <label for="map-opacity">影像不透明度</label>
            <el-slider id="map-opacity" v-model="opacity" :disabled="!loadedLayer" :min="0" :max="100" aria-label="影像不透明度" @input="changeOpacity" />
            <el-button v-if="loadedLayer" link @click="clearResult">移除当前结果</el-button>
          </div>
        </el-popover>
      </div>
      <div class="workspace-body">
        <div class="map-canvas-frame">
          <div ref="mapTarget" class="map-canvas" aria-label="监测地图画布" />
          <div class="map-actions">
            <el-button :icon="Aim" :disabled="!loadedLayer" :loading="locating" @click="locateLoadedLayer">定位结果</el-button>
            <el-button :icon="FullScreen" @click="resetView">全图</el-button>
          </div>
          <div v-if="loadedLayer" class="current-result">
            <span>当前查看</span><strong>{{ mapResultTitle(loadedLayer) }}</strong><small>{{ mapResultDetail(loadedLayer) }}</small>
            <small v-if="evidenceStatus" role="status">{{ evidenceStatus }}</small>
            <small v-if="wmsFailed" role="status">部分结果影像暂时无法显示。</small>
          </div>
          <div v-else class="map-empty-hint"><el-icon><MapLocation /></el-icon><span>选择分析结果，或切换到区域开始圈定范围。</span></div>
        </div>
        <aside v-show="panelOpen" class="work-panel" :aria-label="modeTitle">
          <header class="panel-heading"><div><span>地图工作</span><h3>{{ modeTitle }}</h3></div><el-button text :icon="Close" aria-label="收起工作面板" @click="panelOpen = false" /></header>
          <div class="panel-content">
            <MapResultPanel v-show="mode === 'results'" :task-id="routeTaskId" :image-id="routeImageId" :initial-task-type="routeTaskType" :loaded-id="loadedLayer?.id" @select="loadResult" @all="router.replace('/map')" />
            <MonitoringRegionPanel v-show="mode === 'regions'" :map="map" :active="panelOpen && mode === 'regions'" :image-id="loadedLayer?.imageId" />
            <MapAssetPanel v-show="mode === 'images'" :active="panelOpen && mode === 'images'" />
          </div>
        </aside>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Aim, Close, DataAnalysis, FullScreen, MapLocation, Picture, Setting } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import Feature from 'ol/Feature'
import WKT from 'ol/format/WKT'
import GeoJSON from 'ol/format/GeoJSON'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import { Fill, Stroke, Style } from 'ol/style'
import MonitoringRegionPanel from '@/components/MonitoringRegionPanel.vue'
import MapResultPanel from '@/components/map/MapResultPanel.vue'
import MapAssetPanel from '@/components/map/MapAssetPanel.vue'
import { getImageDetailApi } from '@/api/image'
import { getTaskResultEvidenceApi } from '@/api/ai'
import { getLayerProxyWmsUrl } from '@/api/layer'
import { TOKEN_KEY } from '@/api/request'
import { useOlMap } from '@/composables/map/useOlMap'
import { isUnauthorized, positiveId } from '@/utils/regionAnalysis'
import { mapResultDetail, mapResultTitle } from '@/utils/mapPresentation'
import { readResultEvidence, sectionLabels } from '@/utils/resultEvidence'
import type { LayerListItem } from '@/types/layer'

type WorkMode = 'results' | 'regions' | 'images'
const modes = [{ key: 'results', label: '分析结果', icon: DataAnalysis }, { key: 'regions', label: '监测区域', icon: MapLocation }, { key: 'images', label: '影像浏览', icon: Picture }] as const
const mode = ref<WorkMode>('results'), panelOpen = ref(true)
const modeTitle = computed(() => modes.find(item => item.key === mode.value)!.label)
const route = useRoute(), router = useRouter()
const routeImageId = computed(() => positiveId(route.query.imageId))
const routeTaskId = computed(() => positiveId(route.query.taskId))
const routeTaskType = computed(() => typeof route.query.taskType === 'string' && ['NDVI', 'NDWI', 'CHANGE_DETECTION'].includes(route.query.taskType) ? route.query.taskType : '')
const routeEvidence = computed(() => readResultEvidence(route.query.resultVersion, route.query.section))
const mapTarget = ref<HTMLElement>(), loadedLayer = ref<LayerListItem>(), locating = ref(false)
const opacity = ref(100), baseMapVisible = ref(true)
const { map, initMap, addWmsLayer, removeWmsLayer, updateWmsOpacity, setBaseLayerVisible, resetView, wmsFailed } = useOlMap({ center: [104, 35], zoom: 5 })
let locationRequest = 0, resize: ResizeObserver | undefined
const evidenceStatus = ref('')
const evidenceSource = new VectorSource()
const evidenceLayer = new VectorLayer({ source: evidenceSource, zIndex: 20,
  style: new Style({ fill: new Fill({ color: '#ffbd343b' }), stroke: new Stroke({ color: '#ffb000', width: 3 }) }) })

function openMode(next: WorkMode) { mode.value = next; panelOpen.value = true }
function loadResult(result: LayerListItem) {
  const name = result.qualifiedLayerName || result.layerName
  if (!name) { ElMessage.warning('这份结果暂时无法显示，请换一份或稍后重试。'); return }
  addWmsLayer({ url: getLayerProxyWmsUrl(result), layers: name, opacity: opacity.value / 100,
    authToken: localStorage.getItem(TOKEN_KEY) || undefined })
  loadedLayer.value = { ...result }
}

watch([loadedLayer, routeTaskId, routeEvidence, map], async ([result, taskId, requested, currentMap], _, onCleanup) => {
  locationRequest++
  locating.value = false
  evidenceSource.clear()
  evidenceStatus.value = ''
  if (!result || !currentMap) return
  if (!requested || !taskId || String(result.taskId) !== String(taskId)) {
    void locateLoadedLayer()
    return
  }
  const request = new AbortController()
  onCleanup(() => request.abort())
  const token = localStorage.getItem(TOKEN_KEY)
  const active = () => !request.signal.aborted && token === localStorage.getItem(TOKEN_KEY)
  evidenceStatus.value = '正在核对分区范围…'
  try {
    const data = await getTaskResultEvidenceApi(String(taskId), requested.version, requested.section, request.signal)
    if (!active()) return
    if (data.fileId !== String(result.id) || data.version !== requested.version || data.section !== requested.section)
      throw new Error('Result evidence identity mismatch')
    const geometry = new GeoJSON().readGeometry(data.geometry, { dataProjection: 'EPSG:4326', featureProjection: 'EPSG:3857' })
    const extent = geometry.getExtent()
    if (!['Polygon', 'MultiPolygon'].includes(geometry.getType()) || !extent.every(Number.isFinite) ||
      extent[0] >= extent[2] || extent[1] >= extent[3]) throw new Error('Invalid result evidence extent')
    evidenceSource.addFeature(new Feature(geometry))
    currentMap.getView().fit(extent, { padding: [70, 40, 100, 40], maxZoom: 17, duration: 350 })
    evidenceStatus.value = `已高亮${sectionLabels[requested.section]}分区`
  } catch (error) {
    if (!request.signal.aborted && isUnauthorized(error) && !localStorage.getItem(TOKEN_KEY)) {
      clearResult()
      ElMessage.warning('登录已失效，请重新登录后查看分区。')
      return
    }
    if (!active()) return
    evidenceStatus.value = '分区范围暂不可用，仍可查看任务结果图层。'
    void locateLoadedLayer()
  }
})
async function locateLoadedLayer() {
  const result = loadedLayer.value
  if (!result) return
  const request = ++locationRequest, token = localStorage.getItem(TOKEN_KEY)
  locating.value = true
  try {
    const image = await getImageDetailApi(result.imageId, true)
    if (request !== locationRequest || token !== localStorage.getItem(TOKEN_KEY)) return
    if (!image.footprintWkt) { ElMessage.warning('这景影像暂时没有可定位的范围。'); return }
    const extent = new WKT().readGeometry(image.footprintWkt, { dataProjection: 'EPSG:4326', featureProjection: 'EPSG:3857' }).getExtent()
    if (!extent.every(Number.isFinite) || extent[0] >= extent[2] || extent[1] >= extent[3]) { ElMessage.warning('影像范围暂时不可用，请稍后重试。'); return }
    map.value?.getView().fit(extent, { padding: [70, 40, 100, 40], maxZoom: 17, duration: 350 })
  } catch { if (request === locationRequest) ElMessage.warning('暂时无法定位影像，仍可拖动地图查看。') }
  finally { if (request === locationRequest) locating.value = false }
}
function clearResult() { locationRequest++; locating.value = false; loadedLayer.value = undefined; removeWmsLayer() }
watch(routeTaskId, (next, previous) => { if (next && next !== previous) clearResult() })
function changeOpacity(value: number | number[]) { updateWmsOpacity((Array.isArray(value) ? value[0]! : value) / 100) }
onMounted(() => {
  if (!mapTarget.value) return
  initMap(mapTarget.value).addLayer(evidenceLayer)
  resize = new ResizeObserver(() => map.value?.updateSize())
  resize.observe(mapTarget.value)
})
onBeforeUnmount(() => { locationRequest++; evidenceSource.clear(); resize?.disconnect() })
</script>

<style scoped>
.map-page { --el-color-primary: #28634c; --el-color-primary-light-3: #62947a; --el-color-primary-light-9: #eff6f0; color: #263e32; }
.map-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 18px; }.map-heading h2 { margin: 0; font-size: 23px; letter-spacing: -.5px; font-weight: 600; }.map-heading p { margin: 7px 0 0; color: #748479; font-size: 13px; }
.map-workspace { height: calc(100dvh - 180px); min-height: 490px; display: flex; flex-direction: column; border: 1px solid #d7e0da; border-radius: 12px; overflow: hidden; background: #fff; }
.workspace-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 14px; border-bottom: 1px solid #e4ebe5; background: #fff; }
.work-modes { display: flex; gap: 4px; padding: 3px; border-radius: 7px; background: #f2f5f1; }.work-modes button { display: flex; align-items: center; gap: 7px; padding: 9px 14px; font: inherit; font-size: 13px; border: 0; border-radius: 5px; color: #728276; background: transparent; cursor: pointer; }.work-modes button.active { color: #285b43; background: #fff; box-shadow: 0 1px 4px #24463116; }.work-modes button:focus-visible { outline: 2px solid #28634c; outline-offset: 2px; }
.workspace-body { display: grid; grid-template-columns: minmax(0, 1fr) 320px; min-height: 0; flex: 1; }.panel-closed .workspace-body { grid-template-columns: minmax(0, 1fr); }.map-canvas-frame { position: relative; min-width: 0; min-height: 0; background: #e5ebe4; }.map-canvas { width: 100%; height: 100%; min-height: 0; }.map-canvas :deep(.ol-full-screen) { display: none; }
.map-actions { position: absolute; top: 13px; right: 13px; display: flex; gap: 7px; }.map-actions .el-button { margin: 0; box-shadow: 0 1px 5px #23442d13; border-color: #d4ded5; }.current-result { position: absolute; bottom: 38px; left: 18px; right: 18px; width: fit-content; max-width: calc(100% - 36px); display: grid; gap: 5px; padding: 12px 16px; border-left: 3px solid #397859; border-radius: 5px; background: #fffffff0; box-shadow: 0 3px 14px #263e3214; pointer-events: none; }.current-result span { font-size: 10px; letter-spacing: 1px; color: #78917e; }.current-result strong { font-size: 13px; font-weight: 550; overflow-wrap: anywhere; }.current-result small { color: #76877b; font-size: 11px; }.map-empty-hint { position: absolute; bottom: 40px; left: 18px; right: 18px; display: flex; align-items: center; gap: 9px; padding: 12px 14px; width: fit-content; max-width: calc(100% - 36px); background: #ffffffed; border: 1px solid #d6e1d7; border-radius: 7px; color: #697d6c; font-size: 12px; line-height: 1.8; pointer-events: none; }
.work-panel { display: flex; flex-direction: column; min-height: 0; border-left: 1px solid #dde6df; background: #fff; }.panel-heading { display: flex; align-items: center; justify-content: space-between; padding: 20px 20px 16px; }.panel-heading span { color: #8b9d8e; font-size: 10px; letter-spacing: 1px; }.panel-heading h3 { font-size: 17px; font-weight: 550; color: #294b39; margin: 6px 0 0; }.panel-content { padding: 0 20px 22px; min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
.display-settings h3 { color: #294b39; margin: 2px 0 20px; font-size: 15px; }.display-settings label { font-size: 13px; color: #4b6456; }.display-settings p { font-size: 12px; color: #7a8b80; margin: 3px 0 20px; }.base-toggle { display: flex; align-items: center; justify-content: space-between; }.display-settings :deep(.el-slider) { margin: 8px 4px 14px; }
@media (max-width: 1050px) { .map-workspace { height: auto; min-height: 0; }.workspace-body { display: flex; flex-direction: column; }.map-canvas-frame { height: 360px; flex-shrink: 0; }.work-panel { border-left: 0; border-top: 1px solid #dde6df; max-height: 420px; }.panel-heading { padding-top: 16px; }.work-modes button { padding: 9px 10px; }.workspace-toolbar { flex-wrap: wrap; gap: 8px; }.map-heading h2 { font-size: 20px; } }
@media (prefers-reduced-motion: reduce) { * { scroll-behavior: auto; } }
</style>
