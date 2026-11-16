<template>
  <section class="map-page" aria-label="地图工作台">
    <div class="workspace-body" :class="{ 'with-left': leftVisible, 'with-right': rightVisible }">
      <aside id="map-left-panel" v-show="leftVisible" class="map-panel-left" aria-label="图层和监测区域" tabindex="-1">
        <header class="pane-heading">
          <h2>图层与区域</h2>
          <el-button text :icon="Close" aria-label="收起图层与区域" @click="togglePanel('left')" />
        </header>
        <div class="pane-tabs" aria-label="图层和区域视图">
          <button v-for="item in leftTabs" :key="item.id" type="button" :aria-pressed="leftTab === item.id" @click="leftTab = item.id">{{ item.label }}</button>
        </div>
        <div class="pane-scroll">
          <section v-show="leftTab === 'layers'" class="layer-settings">
            <h3>当前结果图层</h3>
            <template v-if="loadedLayer">
              <p class="layer-title">{{ mapResultTitle(loadedLayer) }}</p>
              <p class="layer-caption">{{ mapResultDetail(loadedLayer) }}</p>
              <label for="map-opacity">影像不透明度</label>
              <el-slider id="map-opacity" v-model="opacity" :min="0" :max="100" aria-label="影像不透明度" @input="changeOpacity" />
              <el-button link @click="clearResult">移除当前结果</el-button>
              <section v-if="loadedLayer.taskType === 'BUILDING_CHANGE_DETECTION'" class="change-layers">
                <h3>建筑物变化检测图层</h3>
                <el-radio-group :model-value="period" aria-label="建筑物变化检测图层" @change="changeLayer">
                  <el-radio-button value="before">前期原图</el-radio-button>
                  <el-radio-button value="after">后期原图</el-radio-button>
                  <el-radio-button value="overlay">变化叠加</el-radio-button>
                </el-radio-group>
              </section>
            </template>
            <p v-else class="layer-caption">在处理结果中选择要查看的图层。</p>
          </section>
          <MonitoringRegionPanel v-show="leftTab === 'regions'" :map="map" :active="leftTab === 'regions'"
            :image-id="loadedLayer?.imageId" @analyze="openRegionForm" @drawing="regionDrawing = $event" />
          <MapAssetPanel v-show="leftTab === 'images'" :active="leftVisible && leftTab === 'images'" />
        </div>
      </aside>

      <div class="map-canvas-frame">
        <div ref="mapTarget" class="map-canvas" aria-label="监测地图画布" tabindex="0" />
        <div class="map-actions">
          <div class="map-action-group">
            <el-button id="map-left-toggle" size="small" :icon="Menu" aria-controls="map-left-panel" :aria-expanded="leftVisible" @click="togglePanel('left')">图层与区域</el-button>
            <el-button id="map-right-toggle" size="small" :icon="DataAnalysis" aria-controls="map-right-panel" :aria-expanded="rightVisible" @click="togglePanel('right')">工具与结果</el-button>
          </div>
          <div class="map-action-group">
            <el-button size="small" :icon="Aim" :disabled="!loadedLayer" :loading="locating" @click="locateLoadedLayer()">定位结果</el-button>
            <el-button size="small" :icon="FullScreen" @click="resetView">全图</el-button>
            <el-popover placement="bottom-end" :width="226" trigger="click">
              <template #reference><el-button size="small" :icon="Setting">底图</el-button></template>
              <div class="base-toggle"><label for="map-base-toggle">参考底图</label><el-switch id="map-base-toggle" v-model="baseMapVisible" aria-label="参考底图" @change="setBaseLayerVisible(baseMapVisible)" /></div>
            </el-popover>
          </div>
        </div>
        <div v-if="detectionFailed.length || wmsFailed" class="map-feedback" role="status">
          {{ detectionFailed.includes('overlay') ? '变化叠加加载失败' : '图层加载失败' }}
          <el-button link @click="retryLayer">重试图层</el-button>
        </div>
        <div v-if="loadedLayer?.taskType === 'BUILDING_CHANGE_DETECTION' && period === 'overlay' && !detectionFailed.length" class="map-legend">
          <span v-if="loadedLayer.hasPredictedChanges !== false" class="legend-swatch" aria-hidden="true"></span>
          {{ loadedLayer.hasPredictedChanges === false ? '没有预测变化' : '预测变化' }}
        </div>
        <footer class="map-status" aria-label="地图状态">
          <span class="map-coordinate">{{ coordinateText }}</span><span class="map-level">级别 {{ zoom }}</span>
          <span class="map-state" role="status">{{ regionDrawing ? '单击添加边界点，双击结束' : mapStatus }}</span>
        </footer>
      </div>

      <aside id="map-right-panel" v-show="rightVisible" class="map-panel-right" aria-label="工具箱和处理结果" tabindex="-1">
        <header class="pane-heading">
          <h2>{{ rightTab === 'results' ? '处理结果' : '工具箱' }}</h2>
          <el-button text :icon="Close" aria-label="收起工具与结果" @click="togglePanel('right')" />
        </header>
        <div class="pane-tabs" aria-label="工具和结果视图">
          <button type="button" :aria-pressed="rightTab === 'tools'" @click="rightTab = 'tools'">工具箱</button>
          <button type="button" :aria-pressed="rightTab === 'results'" @click="rightTab = 'results'">处理结果</button>
        </div>
        <div class="pane-scroll">
          <div v-show="rightTab === 'tools'">
            <template v-if="regionForm">
              <el-button link @click="regionForm = undefined">返回工具箱</el-button>
              <RegionAnalysisForm embedded :key="regionForm.version" :initial-image-id="regionForm.imageId"
                :initial-region-id="regionForm.regionId" @choose-region="showRegions" />
            </template>
            <AnalysisToolbox v-else-if="rightVisible && rightTab === 'tools'" stacked embedded-region @region="openRegionForm(loadedLayer?.imageId)" />
          </div>
          <div v-show="rightTab === 'results'">
            <MapTaskSummary :task-id="loadedLayer?.taskId ?? routeTaskId"
              :model="(loadedLayer?.taskType || routeTaskType) === 'BUILDING_CHANGE_DETECTION'" />
            <MapResultPanel :task-id="routeTaskId" :image-id="routeImageId"
              :initial-task-type="routeTaskType" :loaded-id="loadedLayer?.id" @select="loadResult" @all="router.replace('/map')" />
          </div>
        </div>
      </aside>
    </div>
  </section>
</template>



<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Aim, Close, DataAnalysis, FullScreen, Menu, Setting } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import Feature from 'ol/Feature'
import WKT from 'ol/format/WKT'
import GeoJSON from 'ol/format/GeoJSON'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import { Fill, Stroke, Style } from 'ol/style'
import type { Extent } from 'ol/extent'
import MonitoringRegionPanel from '@/components/MonitoringRegionPanel.vue'
import MapResultPanel from '@/components/map/MapResultPanel.vue'
import MapAssetPanel from '@/components/map/MapAssetPanel.vue'
import MapTaskSummary from '@/components/map/MapTaskSummary.vue'
import AnalysisToolbox from '@/components/analysis/AnalysisToolbox.vue'
import RegionAnalysisForm from '@/components/analysis/RegionAnalysisForm.vue'
import { getImageDetailApi } from '@/api/image'
import { getTaskResultEvidenceApi } from '@/api/ai'
import { getLayerProxyWmsUrl } from '@/api/layer'
import { TOKEN_KEY } from '@/api/request'
import { useOlMap } from '@/composables/map/useOlMap'
import { useDetectionWms, type DetectionPeriod } from '@/composables/map/useDetectionWms'
import { isUnauthorized, positiveId } from '@/utils/regionAnalysis'
import { mapResultDetail, mapResultTitle } from '@/utils/mapPresentation'
import { readResultEvidence, sectionLabels } from '@/utils/resultEvidence'
import type { LayerListItem } from '@/types/layer'

const route = useRoute(), router = useRouter()
const routeImageId = computed(() => positiveId(route.query.imageId))
const routeTaskId = computed(() => positiveId(route.query.taskId))
const routeTaskType = computed(() => typeof route.query.taskType === 'string' && ['NDVI', 'NDWI', 'CHANGE_DETECTION', 'BUILDING_CHANGE_DETECTION'].includes(route.query.taskType) ? route.query.taskType : '')
const routeEvidence = computed(() => readResultEvidence(route.query.resultVersion, route.query.section))
const leftTabs = [{ id: 'layers', label: '图层' }, { id: 'regions', label: '监测区域' }, { id: 'images', label: '影像' }] as const
type Side = 'left' | 'right'
const leftTab = ref<(typeof leftTabs)[number]['id']>(route.query.panel === 'regions' ? 'regions' : 'layers')
const rightTab = ref<'tools' | 'results'>(routeTaskId.value || routeImageId.value ? 'results' : 'tools')
const screen = window.matchMedia('(max-width: 900px)')
const narrow = ref(screen.matches)
const mobilePanel = ref<Side | undefined>(route.query.panel === 'regions' ? 'left' : routeTaskId.value || routeImageId.value ? 'right' : undefined)
const leftOpen = ref(true), rightOpen = ref(true)
const leftVisible = computed(() => narrow.value ? mobilePanel.value === 'left' : leftOpen.value)
const rightVisible = computed(() => narrow.value ? mobilePanel.value === 'right' : rightOpen.value)
const regionDrawing = ref(false)
const regionForm = ref<{ imageId?: number; regionId?: number; version: number }>()
let regionFormVersion = 0
const mapTarget = ref<HTMLElement>(), loadedLayer = ref<LayerListItem>(), locating = ref(false)
let resultExtent: Extent | undefined
const opacity = ref(100), baseMapVisible = ref(true)
const period = ref<DetectionPeriod>('overlay')
const { map, initMap, setWmsLayers, removeWmsLayer, updateWmsOpacity, setBaseLayerVisible, resetView, wmsFailed, pointerLonLat, zoom } = useOlMap({ center: [104, 35], zoom: 5 })
const detection = useDetectionWms()
const { failed: detectionFailed, pending: detectionPending } = detection
let locationRequest = 0, resize: ResizeObserver | undefined
const evidenceStatus = ref('')
const evidenceSource = new VectorSource()
const evidenceLayer = new VectorLayer({ source: evidenceSource, zIndex: 20,
  style: new Style({ fill: new Fill({ color: '#ffbd343b' }), stroke: new Stroke({ color: '#ffb000', width: 3 }) }) })

const coordinateText = computed(() => pointerLonLat.value?.map(coordinate => coordinate.toFixed(5) + '°').join(' / ') || '经纬度 —')
const mapStatus = computed(() => {
  if (wmsFailed.value || detectionFailed.value.length) return '图层加载失败'
  if (locating.value || detectionPending.value) return '正在加载当前视窗…'
  return evidenceStatus.value || (loadedLayer.value ? '已加载结果图层' : '未选择结果图层')
})

function togglePanel(side: Side) {
  const wasOpen = side === 'left' ? leftVisible.value : rightVisible.value
  if (narrow.value) mobilePanel.value = mobilePanel.value === side ? undefined : side
  else if (side === 'left') leftOpen.value = !leftOpen.value
  else rightOpen.value = !rightOpen.value
  void nextTick(() => document.getElementById(`map-${side}-${wasOpen ? 'toggle' : 'panel'}`)?.focus({ preventScroll: true }))
}

function showRegions() {
  leftTab.value = 'regions'
  leftOpen.value = true
  if (narrow.value) mobilePanel.value = 'left'
}

function showResults() {
  rightTab.value = 'results'
  rightOpen.value = true
  if (narrow.value) mobilePanel.value = 'right'
}

function openRegionForm(imageId?: number, regionId?: number) {
  regionForm.value = { imageId, regionId, version: ++regionFormVersion }
  rightTab.value = 'tools'
  rightOpen.value = true
  if (narrow.value) mobilePanel.value = 'right'
}

function retryLayer() {
  if (loadedLayer.value?.taskType === 'BUILDING_CHANGE_DETECTION') detection.select(period.value, true)
  else if (loadedLayer.value) loadResult(loadedLayer.value)
}

function resizePanels() { narrow.value = screen.matches }

function loadResult(result: LayerListItem) {
  if (!result.qualifiedLayerName && !result.layerName) {
    ElMessage.warning('这份结果暂时无法显示，请换一份或稍后重试。')
    return
  }
  const same = loadedLayer.value?.id === result.id
  if (!same) {
    resultExtent = undefined
    period.value = 'overlay'
    removeWmsLayer()
    detection.clear()
  }
  loadedLayer.value = { ...result }
  if (result.taskType === 'BUILDING_CHANGE_DETECTION') {
    if (same) {
      if (resultExtent) detection.select(period.value, true)
      else void locateLoadedLayer(0)
    }
  } else {
    const url = getLayerProxyWmsUrl(result)
    setWmsLayers([{
      layers: (result.qualifiedLayerName || result.layerName)!,
      url, opacity: opacity.value / 100, authToken: localStorage.getItem(TOKEN_KEY) || undefined,
    }])
  }
}
function changeLayer(value: unknown) {
  if (!loadedLayer.value || (value !== 'before' && value !== 'after' && value !== 'overlay')) return
  period.value = value
  detection.select(value)
}

watch([() => loadedLayer.value?.id, routeEvidence, () => map.value], async ([, requested, currentMap], _, onCleanup) => {
  const result = loadedLayer.value
  const taskId = routeTaskId.value
  locationRequest++
  locating.value = false
  evidenceSource.clear()
  evidenceStatus.value = ''
  if (!result || !currentMap) return
  if (result.taskType === 'BUILDING_CHANGE_DETECTION') {
    void locateLoadedLayer(0)
    return
  }
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
async function locateLoadedLayer(duration = 350) {
  const result = loadedLayer.value
  if (!result) return
  const request = ++locationRequest, token = localStorage.getItem(TOKEN_KEY)
  locating.value = true
  try {
    const currentMap = map.value
    if (!currentMap) return
    const needsDetection = result.taskType === 'BUILDING_CHANGE_DETECTION'
    let extent = resultExtent || readFootprint(result.footprintWkt, result.footprintCrs)
    if (!extent) {
      const image = await getImageDetailApi(result.imageId, true)
      extent = readFootprint(image.footprintWkt, 'EPSG:4326')
    }
    if (request !== locationRequest || token !== localStorage.getItem(TOKEN_KEY)) return
    if (!extent) { ElMessage.warning('影像范围暂时不可用，请稍后重试。'); return }
    const firstDetectionView = needsDetection && !resultExtent
    currentMap.getView().fit(extent, { padding: [70, 40, 100, 40], maxZoom: 17, ...(duration && !firstDetectionView ? { duration } : {}) })
    if (firstDetectionView) {
      resultExtent = extent
      detection.start(currentMap, result, extent, period.value)
    }
  } catch { if (request === locationRequest) ElMessage.warning('暂时无法定位影像，请重试定位。') }
  finally { if (request === locationRequest) locating.value = false }
}
function readFootprint(wkt?: string, crs?: string) {
  if (!wkt || (crs !== 'EPSG:4326' && crs !== 'EPSG:3857')) return
  try {
    const extent = new WKT().readGeometry(wkt, { dataProjection: crs, featureProjection: 'EPSG:3857' }).getExtent()
    if (extent.every(Number.isFinite) && extent[0] < extent[2] && extent[1] < extent[3]) return extent
  } catch {
    return
  }
}
function clearResult() {
  locationRequest++
  locating.value = false
  loadedLayer.value = undefined
  resultExtent = undefined
  detection.clear()
  removeWmsLayer()
}
watch(routeTaskId, (next, previous) => {
  if (next && next !== previous) { clearResult(); showResults() }
})
watch(routeImageId, id => { if (id) showResults() })
watch(() => route.query.panel, panel => { if (panel === 'regions') showRegions() })
function changeOpacity(value: number | number[]) {
  const fraction = (Array.isArray(value) ? value[0]! : value) / 100
  updateWmsOpacity(fraction)
  detection.setOpacity(fraction)
}
onMounted(() => {
  screen.addEventListener('change', resizePanels)
  if (!mapTarget.value) return
  initMap(mapTarget.value).addLayer(evidenceLayer)
  resize = new ResizeObserver(() => map.value?.updateSize())
  resize.observe(mapTarget.value)
})
onBeforeUnmount(() => {
  screen.removeEventListener('change', resizePanels)
  locationRequest++
  detection.clear()
  evidenceSource.clear()
  resize?.disconnect()
})
</script>

<style scoped>
.map-page { height: calc(100dvh - var(--work-header-height)); min-height: 320px; }
.workspace-body { position: relative; display: grid; grid-template-columns: 0 minmax(0, 1fr) 0; height: 100%; }
.workspace-body.with-left { grid-template-columns: 224px minmax(0, 1fr) 0; }
.workspace-body.with-right { grid-template-columns: 0 minmax(0, 1fr) 328px; }
.workspace-body.with-left.with-right { grid-template-columns: 224px minmax(0, 1fr) 328px; }
.map-panel-left, .map-panel-right { display: flex; flex-direction: column; min-height: 0; min-width: 0; overflow: hidden; background: var(--work-panel); }
.map-panel-left { grid-column: 1; grid-row: 1; border-right: 1px solid var(--work-border); }
.map-panel-right { grid-column: 3; grid-row: 1; border-left: 1px solid var(--work-border); }
.pane-heading { display: flex; align-items: center; justify-content: space-between; min-height: 46px; padding-inline: 13px 7px; border-bottom: 1px solid var(--work-border); }
.pane-heading h2 { margin: 0; font-size: 14px; }
.pane-tabs { display: flex; padding: 0 10px; border-bottom: 1px solid var(--work-border); }
.pane-tabs button { flex: 1; min-width: 0; padding: 10px 4px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--work-muted); font: inherit; font-size: 12px; cursor: pointer; }
.pane-tabs button[aria-pressed="true"] { color: var(--work-accent); border-bottom-color: var(--work-accent); }
.pane-scroll { min-height: 0; padding: 13px 12px 20px; overflow-y: auto; overflow-x: hidden; overscroll-behavior: contain; }
.layer-settings { font-size: 12px; }
.layer-settings h3 { margin: 0 0 9px; font-size: 13px; }
.layer-title { margin: 0; overflow-wrap: anywhere; line-height: 1.55; }
.layer-caption { margin: 6px 0 12px; color: var(--work-muted); line-height: 1.55; }
.layer-settings label { color: var(--work-secondary); }
.layer-settings :deep(.el-slider) { width: calc(100% - 10px); margin-inline: 5px; }
.change-layers { margin-top: 16px; padding-top: 13px; border-top: 1px solid var(--work-border); }
.change-layers :deep(.el-radio-group) { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); width: 100%; }
.change-layers :deep(.el-radio-button__inner) { width: 100%; padding: 8px 3px; font-size: 11px; }
.change-layers :deep(.el-radio-button:focus-within) { z-index: 1; box-shadow: inset 0 0 0 2px var(--work-accent); }
.legend-swatch { width: 9px; height: 9px; border-radius: 2px; background: #d03832; }
.map-canvas-frame { position: relative; display: grid; grid-template-rows: minmax(0, 1fr) 30px; grid-column: 2; grid-row: 1; min-width: 0; min-height: 0; background: #242e3b; }
.map-canvas { width: 100%; height: 100%; min-height: 0; }
.map-canvas:focus-visible { outline-offset: -2px; }
.map-canvas :deep(.ol-full-screen) { display: none; }
.map-canvas :deep(.ol-zoom) { inset: auto 12px 37px auto; padding: 3px; background: #1e2229e8; border: 1px solid #515d6e; }
.map-canvas :deep(.ol-zoom button) { color: var(--work-text); background: var(--work-hover); }
.map-canvas :deep(.ol-scale-line) { bottom: 10px; background: #1b222dde; }
.map-canvas :deep(.ol-scale-line-inner) { color: #e1e9f5; border-color: #b2c4db; font-family: var(--work-mono); }
.map-canvas :deep(.ol-attribution) { bottom: 4px; background: #e3eaf0eb; }
.map-actions { position: absolute; inset: 10px 10px auto; z-index: 3; display: flex; flex-wrap: wrap; justify-content: space-between; gap: 6px; pointer-events: none; }
.map-action-group { display: flex; gap: 4px; pointer-events: auto; }
.map-action-group .el-button, .map-action-group :deep(.el-button) { margin: 0; box-shadow: 0 2px 8px #0e172441; }
.base-toggle { display: flex; align-items: center; justify-content: space-between; }
.map-feedback { position: absolute; top: 43px; left: 10px; z-index: 2; display: flex; align-items: center; gap: 8px; padding: 5px 9px; border-radius: 4px; color: var(--work-warning); background: #222832f2; font-size: 12px; }
.map-legend { position: absolute; bottom: 64px; left: 10px; display: flex; align-items: center; gap: 7px; padding: 6px 8px; color: var(--work-text); background: #1b222de8; border: 1px solid var(--work-border); border-radius: 4px; font-size: 12px; }
.map-status { display: flex; min-width: 0; align-items: center; gap: 14px; padding-inline: 10px; background: var(--work-inset); color: var(--work-secondary); border-top: 1px solid var(--work-border); font-size: 11px; }
.map-coordinate, .map-level { font-family: var(--work-mono); white-space: nowrap; }
.map-state { margin-left: auto; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 900px) {
  .workspace-body, .workspace-body.with-left, .workspace-body.with-right, .workspace-body.with-left.with-right { grid-template-columns: minmax(0, 1fr); }
  .map-canvas-frame { grid-column: 1; }
  .map-panel-left, .map-panel-right { position: absolute; z-index: 5; top: 68px; bottom: 94px; width: min(328px, calc(100% - 44px)); border: 1px solid var(--work-border); border-radius: 6px; box-shadow: 0 10px 24px #0d131c88; }
  .map-panel-left { left: 7px; }
  .map-panel-right { right: 7px; }
  .map-actions { inset: 8px 6px auto; }
  .map-canvas :deep(.ol-zoom) { bottom: 12px; }
  .map-feedback { top: 67px; z-index: 6; }
  .map-status { gap: 7px; padding-inline: 6px; font-size: 10px; }
  .map-level { display: none; }
}
</style>
