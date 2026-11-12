<template>
  <section class="region-workflow" aria-label="监测区域管理">
    <p v-if="error" class="feedback" role="status">{{ error }}</p>
    <template v-if="!editing">
      <p class="intro">选一个已有区域，或在地图上圈定新范围。</p>
      <el-form label-position="top"><el-form-item label="我的区域">
        <el-select v-model="selectedId" :loading="loading" filterable clearable class="full-width" placeholder="选择已保存区域" @change="selectRegion">
          <el-option v-for="region in regions" :key="region.id" :value="region.id" :label="region.name" />
        </el-select>
      </el-form-item></el-form>
      <div v-if="selectedRegion" class="region-summary">
        <span class="saved-label">已保存的范围</span><h4>{{ selectedRegion.name }}</h4>
        <el-button type="primary" class="main-action" @click="analyze">用这个区域分析</el-button>
        <el-button link @click="startEditor(true)">修改边界或名称</el-button>
      </div>
      <p v-else-if="!loading && !regions.length" class="empty">还没有保存的区域，从地图上圈一个开始。</p>
      <div class="new-region"><el-button :icon="Plus" :disabled="!map || loading" @click="startEditor(false)">绘制新区域</el-button><el-button v-if="error && !loading" link @click="loadRegions">重新读取</el-button></div>
    </template>
    <template v-else>
      <div class="edit-step"><span>{{ drawing ? '01 / 圈定范围' : '02 / 保存区域' }}</span><h4>{{ drawing ? '在地图上画出范围' : '给这个区域起个名字' }}</h4></div>
      <template v-if="drawing">
        <p class="draw-help">单击放置边界点，双击结束。</p>
        <div class="draw-note"><el-icon><InfoFilled /></el-icon><p>沿影像圈定，范围需完整落在影像内。参考底图可能存在偏移。</p></div>
        <el-button class="main-action" @click="cancelEditor">取消绘制</el-button>
      </template>
      <template v-else>
        <p class="intro">范围已圈定，保存后即可用于分析。</p>
        <el-form label-position="top" @submit.prevent="save"><el-form-item label="区域名称">
          <el-input v-model="name" :disabled="saving" maxlength="100" placeholder="例如：样区 A" />
        </el-form-item></el-form>
        <el-button type="primary" class="main-action" :loading="saving" :disabled="!geometry || !name.trim()" @click="save">保存区域</el-button>
        <div class="edit-actions"><el-button link :disabled="saving" @click="drawBoundary">重新绘制</el-button><el-button link :disabled="saving" @click="cancelEditor">取消修改</el-button></div>
        <p v-if="editingId" class="quiet-note">修改后，已有分析结果保持不变。</p>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { InfoFilled, Plus } from '@element-plus/icons-vue'
import type Map from 'ol/Map'
import Draw from 'ol/interaction/Draw'
import GeoJSON from 'ol/format/GeoJSON'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import { Fill, Stroke, Style } from 'ol/style'
import { listMonitoringRegionsApi, saveMonitoringRegionApi, type MonitoringRegion, type RegionPolygon } from '@/api/monitoringRegion'
import { TOKEN_KEY } from '@/api/request'

const props = withDefaults(defineProps<{ map?: Map; active?: boolean; imageId?: number }>(), { active: true })
const router = useRouter()
const regions = ref<MonitoringRegion[]>([]), selectedId = ref<number>(), editingId = ref<number>()
const selectedRegion = computed(() => regions.value.find(region => region.id === selectedId.value))
const name = ref(''), geometry = ref<RegionPolygon>(), drawing = ref(false), saving = ref(false), loading = ref(false), editing = ref(false), error = ref('')
const source = new VectorSource()
const layer = new VectorLayer({ source, zIndex: 30, style: new Style({
  stroke: new Stroke({ color: '#cf982e', width: 3 }), fill: new Fill({ color: 'rgba(227,165,45,0.10)' }),
}) })
const format = new GeoJSON()
let draw: Draw | undefined, request = 0, alive = true

watch(() => props.map, (map, oldMap) => {
  if (draw) oldMap?.removeInteraction(draw)
  draw = undefined; drawing.value = false
  oldMap?.removeLayer(layer); map?.addLayer(layer)
}, { immediate: true })
// A hidden panel must never leave a drawing interaction on the shared canvas.
watch(() => props.active, active => { if (!active && drawing.value) cancelEditor() })
onMounted(loadRegions)
onBeforeUnmount(() => { alive = false; request++; stopDrawing(); props.map?.removeLayer(layer) })

async function loadRegions() {
  const call = ++request, token = localStorage.getItem(TOKEN_KEY)
  loading.value = true; error.value = ''
  try {
    const result = await listMonitoringRegionsApi(true)
    if (call === request && token === localStorage.getItem(TOKEN_KEY)) regions.value = result
  } catch { if (call === request) error.value = '暂时无法读取区域，请重新读取。' }
  finally { if (call === request) loading.value = false }
}
function showGeometry(value?: RegionPolygon) {
  source.clear()
  if (value) source.addFeatures(format.readFeatures({ type: 'Feature', geometry: value }, { dataProjection: 'EPSG:4326', featureProjection: 'EPSG:3857' }))
}
function savedGeometry() {
  try { return selectedRegion.value ? JSON.parse(selectedRegion.value.geometryJson) as RegionPolygon : undefined }
  catch { error.value = '这个区域的范围暂时无法读取，请换一个区域。'; return undefined }
}
function selectRegion() {
  error.value = ''
  const selected = savedGeometry()
  showGeometry(selected)
  const extent = source.getExtent()
  if (selected && extent && extent.every(Number.isFinite)) props.map?.getView().fit(extent, { padding: [70, 40, 100, 40], maxZoom: 17, duration: 300 })
}
function stopDrawing() { if (draw) props.map?.removeInteraction(draw); draw = undefined; drawing.value = false }
function cancelEditor() {
  if (saving.value) return
  stopDrawing(); editing.value = false; editingId.value = undefined; geometry.value = undefined; name.value = ''; error.value = ''
  showGeometry(savedGeometry())
}
function startEditor(edit: boolean) {
  if (!props.map || saving.value) return
  error.value = ''; editing.value = true
  editingId.value = edit ? selectedId.value : undefined
  geometry.value = edit ? savedGeometry() : undefined
  name.value = edit ? selectedRegion.value?.name || '' : ''
  if (edit && geometry.value) showGeometry(geometry.value)
  else drawBoundary()
}
function drawBoundary() {
  if (!props.map || saving.value) return
  stopDrawing(); source.clear(); drawing.value = true; error.value = ''
  draw = new Draw({ type: 'Polygon', maxPoints: 500 })
  draw.on('drawend', event => {
    geometry.value = format.writeGeometryObject(event.feature.getGeometry()!, { featureProjection: 'EPSG:3857', dataProjection: 'EPSG:4326' }) as RegionPolygon
    stopDrawing(); showGeometry(geometry.value)
  })
  props.map.addInteraction(draw)
}
async function save() {
  if (saving.value || drawing.value || !geometry.value || !name.value.trim()) return
  const token = localStorage.getItem(TOKEN_KEY)
  saving.value = true; error.value = ''
  try {
    const result = await saveMonitoringRegionApi(name.value.trim(), geometry.value, editingId.value)
    if (!alive || token !== localStorage.getItem(TOKEN_KEY)) return
    regions.value = [result, ...regions.value.filter(item => item.id !== result.id)].slice(0, 100)
    selectedId.value = result.id; editing.value = false; editingId.value = undefined; geometry.value = undefined; name.value = ''
    selectRegion(); ElMessage.success('区域已保存')
  } catch { if (alive) error.value = '保存未完成，范围和名称已保留，请重试。' }
  finally { if (alive) saving.value = false }
}
function analyze() {
  if (!selectedRegion.value || editing.value) return
  void router.push({ path: '/tasks/create', query: { mode: 'region-ndvi', monitoringRegionId: selectedRegion.value.id,
    ...(props.imageId ? { imageId: props.imageId } : {}) } })
}
</script>

<style scoped>
.region-workflow { color: #344e3e; }.intro { margin: 0 0 20px; color: #74877a; font-size: 13px; line-height: 1.8; }.region-workflow :deep(.el-form-item__label) { font-size: 13px; color: #536b5c; }.region-summary { margin: 22px 0; padding: 18px 16px; border-left: 3px solid #5c8667; background: #f3f7f1; border-radius: 5px; }.saved-label { font-size: 10px; color: #7c927f; letter-spacing: 1px; }.region-summary h4 { font-weight: 550; font-size: 15px; margin: 10px 0 22px; overflow-wrap: anywhere; }.main-action { width: 100%; margin: 0 0 12px; }.region-summary > .el-button.is-link { display: flex; margin: 0 auto; font-size: 12px; }.new-region { display: grid; gap: 12px; border-top: 1px solid #e6ece5; margin-top: 24px; padding-top: 20px; }.new-region .el-button { margin: 0; }.edit-step { margin: 4px 0 18px; }.edit-step span { color: #8c9a83; font-size: 11px; }.edit-step h4 { margin: 10px 0 0; font-size: 17px; font-weight: 550; color: #31553d; }.draw-help { font-size: 14px; line-height: 1.8; margin: 0 0 16px; }.draw-note { display: flex; align-items: flex-start; gap: 9px; color: #8d7545; padding: 12px 13px; margin-bottom: 26px; border-radius: 6px; background: #f8f5ed; }.draw-note .el-icon { flex-shrink: 0; margin-top: 3px; }.draw-note p { margin: 0; font-size: 12px; line-height: 1.8; }.edit-actions { display: flex; justify-content: space-between; margin-top: 4px; }.edit-actions .el-button { margin: 0; }.quiet-note, .empty { font-size: 12px; color: #849385; line-height: 1.8; }.quiet-note { margin-top: 24px; }.feedback { color: #96612d; font-size: 13px; line-height: 1.8; margin: 0 0 16px; }
</style>
