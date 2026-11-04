<template>
  <div class="product-map-frame">
    <div class="map-heading">
      <div><strong>在产品预览上选择区域</strong><span>完整覆盖 · 不超过 2048 × 2048 源像素</span></div>
      <div class="map-actions">
        <el-button size="small" :disabled="!product?.previewGrid || loading" @click="startDrawing">绘制新区域</el-button>
        <el-button v-if="drawing" size="small" @click="cancelDrawing">取消绘制</el-button>
        <el-button size="small" :disabled="!product?.previewGrid" @click="fitProduct">定位产品</el-button>
      </div>
    </div>
    <div ref="target" class="product-map" aria-label="产品预览与区域绘制地图" />
    <div v-if="!product?.previewGrid" class="empty-map">产品检查完成后，定位预览将显示在这里。</div>
    <div v-if="loading" class="map-note">正在读取私有预览…</div>
    <div v-else-if="error" class="map-note map-error">{{ error }} <button type="button" @click="loadPreview">重试预览</button></div>
    <div v-else-if="drawing" class="map-note">单击添加顶点，双击结束；取消后保留原区域。</div>
    <div class="map-footer"><span>预览只用于定位；裁剪窗口以服务端预检为准。</span><span>{{ coordinates }}</span></div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Map from 'ol/Map'
import View from 'ol/View'
import ImageLayer from 'ol/layer/Image'
import ImageStatic from 'ol/source/ImageStatic'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import Draw from 'ol/interaction/Draw'
import GeoJSON from 'ol/format/GeoJSON'
import { Fill, Stroke, Style } from 'ol/style'
import { defaults as controls, ScaleLine } from 'ol/control'
import { get as getProjection, toLonLat, transformExtent } from 'ol/proj'
import type { Extent } from 'ol/extent'
import type { RegionPolygon } from '@/api/monitoringRegion'
import type { ProductView } from '@/types/ingestion'
import { describeIngestionError, previewBlob } from '@/api/ingestion'
import 'ol/ol.css'

const props = defineProps<{ product?: ProductView; geometry?: RegionPolygon }>()
const emit = defineEmits<{ geometry: [geometry: RegionPolygon] }>()
const target = ref<HTMLElement>()
const loading = ref(false), drawing = ref(false), error = ref(''), coordinates = ref('')
const image = new ImageLayer<ImageStatic>({ zIndex: 1 })
const regionSource = new VectorSource(), footprintSource = new VectorSource()
const regions = new VectorLayer({ source: regionSource, zIndex: 3, style: new Style({ stroke: new Stroke({ color: '#d78a35', width: 2.5 }), fill: new Fill({ color: 'rgba(215,138,53,.12)' }) }) })
const footprint = new VectorLayer({ source: footprintSource, zIndex: 2, style: new Style({ stroke: new Stroke({ color: '#4a776a', width: 1.5, lineDash: [5, 5] }) }) })
const geojson = new GeoJSON()
let map: Map | undefined, draw: Draw | undefined, objectUrl: string | undefined, abort: AbortController | undefined, generation = 0

onMounted(() => {
  map = new Map({ target: target.value, view: new View({ center: [0, 0], zoom: 2 }), layers: [image, footprint, regions], controls: controls().extend([new ScaleLine()]) })
  map.on('pointermove', event => { const point = toLonLat(event.coordinate); coordinates.value = `${point[0]?.toFixed(5)}°  ${point[1]?.toFixed(5)}°` })
  showGeometry(); void loadPreview()
})
watch(() => `${props.product?.productId || ''}:${props.product?.status || ''}`, () => { cancelDrawing(); void loadPreview() })
watch(() => props.geometry, showGeometry, { deep: true })

function showGeometry() {
  regionSource.clear()
  if (props.geometry) {
    const feature = geojson.readFeature(props.geometry, { dataProjection: 'EPSG:4326', featureProjection: 'EPSG:3857' })
    regionSource.addFeatures(Array.isArray(feature) ? feature : [feature])
  }
}
function fitProduct() {
  const grid = props.product?.previewGrid
  if (grid?.bounds && getProjection(grid.crs)) map?.getView().fit(transformExtent(grid.bounds as Extent, grid.crs, 'EPSG:3857', 8), { padding: [35, 35, 35, 35], duration: 250 })
}
async function loadPreview() {
  if (!map) return
  const current = ++generation
  abort?.abort(); abort = new AbortController()
  image.setSource(null); footprintSource.clear(); error.value = ''; loading.value = false
  if (objectUrl) { URL.revokeObjectURL(objectUrl); objectUrl = undefined }
  const product = props.product
  if (!product?.previewGrid || product.status !== 'READY') return
  if (!getProjection(product.previewGrid.crs)) { error.value = '当前源投影暂不可用。'; return }
  if (product.footprint) {
    const feature = geojson.readFeature(product.footprint, { dataProjection: 'EPSG:4326', featureProjection: 'EPSG:3857' })
    footprintSource.addFeatures(Array.isArray(feature) ? feature : [feature])
  }
  fitProduct(); loading.value = true
  try {
    const blob = await previewBlob(product.productId, abort.signal)
    if (current !== generation) return
    objectUrl = URL.createObjectURL(blob)
    image.setSource(new ImageStatic({ url: objectUrl, projection: product.previewGrid.crs, imageExtent: product.previewGrid.bounds as Extent }))
  } catch (failure) { if (current === generation && !abort.signal.aborted) error.value = describeIngestionError(failure).message }
  finally { if (current === generation) loading.value = false }
}
function cancelDrawing() { if (draw) map?.removeInteraction(draw); draw = undefined; drawing.value = false }
function startDrawing() {
  cancelDrawing(); drawing.value = true
  draw = new Draw({ type: 'Polygon', maxPoints: 500, wrapX: false })
  draw.on('drawend', event => {
    const geometry = event.feature.getGeometry()
    if (geometry) emit('geometry', geojson.writeGeometryObject(geometry, { dataProjection: 'EPSG:4326', featureProjection: 'EPSG:3857', decimals: 12 }) as RegionPolygon)
    cancelDrawing()
  })
  map?.addInteraction(draw)
}
onBeforeUnmount(() => { generation++; abort?.abort(); cancelDrawing(); if (objectUrl) URL.revokeObjectURL(objectUrl); map?.setTarget(undefined); map?.dispose() })
</script>

<style scoped>
.product-map-frame{position:relative;border:1px solid #d8e1dc;border-radius:10px;overflow:hidden;background:#edf2ee}
.map-heading{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:17px 20px;background:#fff;border-bottom:1px solid #d8e1dc}
.map-heading strong{display:block;font-size:15px;color:#233f35}.map-heading span{display:block;font-size:12px;color:#68786f;margin-top:5px}.map-actions{display:flex;flex-wrap:wrap;gap:6px}.map-actions .el-button{margin-left:0}
.product-map{height:440px;background-color:#e7ede8;background-image:linear-gradient(#d4dfd74d 1px,transparent 1px),linear-gradient(90deg,#d4dfd74d 1px,transparent 1px);background-size:32px 32px}
.empty-map{position:absolute;left:24px;top:48%;right:24px;text-align:center;color:#63766a;pointer-events:none}.map-note{position:absolute;bottom:47px;left:16px;right:16px;background:#ffffffed;border:1px solid #d6dfd9;padding:10px 14px;border-radius:5px;font-size:13px;color:#334e41}.map-error{color:#9d3e2b}.map-note button{border:0;background:none;text-decoration:underline;color:inherit;cursor:pointer}
.map-footer{display:flex;justify-content:space-between;gap:12px;padding:11px 16px;background:#f8faf8;color:#6b7a70;font-size:11px}
@media(max-width:800px){.map-heading{align-items:flex-start;flex-direction:column}.product-map{height:360px}.map-footer{flex-direction:column}}
</style>
