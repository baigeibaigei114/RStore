<template>
  <el-card class="region-panel" shadow="never">
    <template #header><span>监测区域 · 单期分析</span></template>
    <p class="region-hint">沿影像绘制边界，双击结束。区域需完整落在影像内。</p>
    <el-alert type="warning" :closable="false" title="请以影像图层定位。当前高德底图与 WGS84 影像可能偏移，不要沿道路底图勾画精确边界。" />
    <el-form label-position="top">
      <el-form-item label="已保存区域（最近 100 个）">
        <el-select v-model="selectedId" :disabled="saving" clearable filterable class="full-width"
          placeholder="选择已保存区域" @change="selectRegion">
          <el-option v-for="region in regions" :key="region.id" :value="region.id"
            :label="`${region.name} · v${region.version}`" />
        </el-select>
      </el-form-item>
      <el-form-item label="区域名称">
        <el-input v-model="name" :disabled="saving" maxlength="100" placeholder="例如：样区 A" @input="dirty = true" />
      </el-form-item>
    </el-form>
    <div class="region-actions">
      <el-button :disabled="!map || saving" @click="startDrawing(false)">新建区域</el-button>
      <el-button :disabled="!selectedId || saving" @click="startDrawing(true)">重绘边界</el-button>
      <el-button v-if="drawing" @click="cancelDrawing">取消绘制</el-button>
      <el-button type="primary" :loading="saving" :disabled="!geometry || drawing || !name.trim()" @click="save">
        {{ selectedId ? '保存区域修改' : '保存新区域' }}
      </el-button>
      <el-button :disabled="!selectedId || dirty || drawing" @click="analyze">创建区域 NDVI</el-button>
    </div>
    <p class="region-hint">{{ drawing ? '正在绘制，最多 500 个顶点；可取消后重画。' : '修改区域不改变历史任务；计算使用提交时保存的区域版本。' }}</p>
  </el-card>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type Map from 'ol/Map'
import Draw from 'ol/interaction/Draw'
import GeoJSON from 'ol/format/GeoJSON'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import { Fill, Stroke, Style } from 'ol/style'
import { listMonitoringRegionsApi, saveMonitoringRegionApi, type MonitoringRegion, type RegionPolygon } from '@/api/monitoringRegion'

const props = defineProps<{ map?: Map }>()
const router = useRouter()
const regions = ref<MonitoringRegion[]>([])
const selectedId = ref<number>()
const name = ref('')
const geometry = ref<RegionPolygon>()
const drawing = ref(false)
const saving = ref(false)
const dirty = ref(false)
const source = new VectorSource()
const layer = new VectorLayer({
  source, zIndex: 30,
  style: new Style({ stroke: new Stroke({ color: '#e3a52d', width: 3 }),
    fill: new Fill({ color: 'rgba(227,165,45,0.08)' }) }),
})
const format = new GeoJSON()
let draw: Draw | undefined

watch(() => props.map, (map, oldMap) => {
  if (draw) oldMap?.removeInteraction(draw)
  draw = undefined
  drawing.value = false
  oldMap?.removeLayer(layer)
  map?.addLayer(layer)
}, { immediate: true })
onMounted(async () => { regions.value = await listMonitoringRegionsApi() })
onBeforeUnmount(() => {
  if (draw) props.map?.removeInteraction(draw)
  props.map?.removeLayer(layer)
})

function displayGeometry() {
  source.clear()
  if (!geometry.value) return
  source.addFeatures(format.readFeatures({ type: 'Feature', geometry: geometry.value }, {
    dataProjection: 'EPSG:4326', featureProjection: 'EPSG:3857',
  }))
}

function cancelDrawing() {
  if (draw) props.map?.removeInteraction(draw)
  draw = undefined
  drawing.value = false
  displayGeometry()
}

function selectRegion() {
  cancelDrawing()
  const region = regions.value.find(item => item.id === selectedId.value)
  name.value = region?.name || ''
  geometry.value = region ? JSON.parse(region.geometryJson) : undefined
  dirty.value = false
  displayGeometry()
  const extent = source.getExtent()
  if (geometry.value && extent) props.map?.getView().fit(extent, { padding: [70, 70, 70, 70], maxZoom: 17 })
}

function startDrawing(edit: boolean) {
  cancelDrawing()
  if (!edit) {
    selectedId.value = undefined
    name.value = ''
    geometry.value = undefined
  }
  source.clear()
  dirty.value = true
  drawing.value = true
  draw = new Draw({ type: 'Polygon', maxPoints: 500 })
  draw.on('drawend', event => {
    geometry.value = format.writeGeometryObject(event.feature.getGeometry()!, {
      featureProjection: 'EPSG:3857', dataProjection: 'EPSG:4326',
    }) as RegionPolygon
    cancelDrawing()
  })
  props.map?.addInteraction(draw)
}

async function save() {
  if (!geometry.value) return
  saving.value = true
  try {
    const result = await saveMonitoringRegionApi(name.value.trim(), geometry.value, selectedId.value)
    regions.value = [result, ...regions.value.filter(item => item.id !== result.id)].slice(0, 100)
    selectedId.value = result.id
    geometry.value = JSON.parse(result.geometryJson)
    dirty.value = false
    ElMessage.success('区域已保存，历史任务保持原快照')
  } finally { saving.value = false }
}

function analyze() {
  router.push({ path: '/tasks/create', query: { monitoringRegionId: selectedId.value } })
}
</script>

<style scoped>
.region-panel { border-top: 3px solid #bb8420; }
.region-hint { color: #66776d; font-size: 13px; line-height: 1.7; }
.region-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.region-actions .el-button { margin-left: 0; }
.el-form { margin-top: 16px; }
</style>
