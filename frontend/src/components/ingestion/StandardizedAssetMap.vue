<template>
  <section class="asset-map-frame" aria-label="标准化资产地图">
    <header><div><span class="eyebrow">PRIVATE / RGB</span><h3>{{ name || '标准化区域影像' }}</h3><p>真彩色 · 红 / 绿 / 蓝 = B04 / B03 / B02</p></div><div class="tools"><el-button size="small" @click="fit">定位影像</el-button><el-button size="small" :loading="downloading" @click="download">下载原生网格</el-button></div></header>
    <div ref="target" class="asset-map" aria-label="私有影像地图画布" />
    <div class="map-status" role="status"><span>{{ error || (loading ? '正在读取私有图像…' : loaded ? '地图图像已加载' : '等待地图图像') }}</span><el-button v-if="error" size="small" @click="refresh">重新读取地图</el-button></div>
    <footer><span>透明区域为 NoData；显示拉伸不改变反射率值。</span><span>本人可见 · 不自动分析</span></footer>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Map from 'ol/Map'
import View from 'ol/View'
import ImageLayer from 'ol/layer/Image'
import ImageWMS from 'ol/source/ImageWMS'
import { transformExtent } from 'ol/proj'
import GeoJSON from 'ol/format/GeoJSON'
import type { Extent } from 'ol/extent'
import type { RegionPolygon } from '@/api/monitoringRegion'
import { assetCoverageBlob, assetMapBlob, describeIngestionError } from '@/api/ingestion'
import { TOKEN_KEY } from '@/api/request'
import { useAuthStore } from '@/stores/auth'
import 'ol/ol.css'

const props = defineProps<{ layerId: string; name?: string; geometry?: RegionPolygon; bounds?: { left: number; bottom: number; right: number; top: number } }>()
const auth = useAuthStore(), target = ref<HTMLElement>(), error = ref(''), loading = ref(false), loaded = ref(false), downloading = ref(false)
const layer = new ImageLayer<ImageWMS>()
let map: Map | undefined, generation = 0, imageGeneration = 0, abort: AbortController | undefined, downloadAbort: AbortController | undefined, objectUrl = '', alive = true, resize: ResizeObserver | undefined
const owned = (token: string) => alive && localStorage.getItem(TOKEN_KEY) === token && !!token
function clear() { generation++; imageGeneration++; abort?.abort(); downloadAbort?.abort(); layer.setSource(null); loaded.value = false; loading.value = false; if (objectUrl) URL.revokeObjectURL(objectUrl); objectUrl = '' }
function fit() {
  let extent: Extent | undefined
  if (props.bounds) { const b = props.bounds; extent = transformExtent([b.left, b.bottom, b.right, b.top], 'EPSG:4326', 'EPSG:3857', 8) }
  else if (props.geometry) extent = new GeoJSON().readGeometry(props.geometry, { dataProjection: 'EPSG:4326', featureProjection: 'EPSG:3857' }).getExtent()
  if (extent?.every(Number.isFinite)) map?.getView().fit(extent, { padding: [35, 35, 35, 35], maxZoom: 19 })
}
function refresh() {
  clear(); error.value = ''
  const token = localStorage.getItem(TOKEN_KEY) || '', current = generation
  if (!map || !owned(token) || !/^[0-9a-f-]{36}$/.test(props.layerId)) return
  // OpenLayers computes extent/size; the adapter constructs a fresh request with only SIX permitted keys.
  // Its SERVICE/VERSION/LAYERS/STYLES/FORMAT parameters never reach Java.
  const source = new ImageWMS({ url: `/api/ingestion/layers/${props.layerId}/wms`, params: { VERSION: '1.1.1' }, ratio: 1, hidpi: false,
    imageLoadFunction: (image, src) => {
      abort?.abort(); abort = new AbortController(); const controller = abort, requestId = ++imageGeneration
      const query = new URL(src, window.location.origin).searchParams
      const width = Number(query.get('WIDTH')), height = Number(query.get('HEIGHT')), scale = Math.min(1, 2048 / Math.max(width, height))
      loading.value = true; loaded.value = false; error.value = ''
      void assetMapBlob(props.layerId, { bbox: query.get('BBOX') || '', srs: 'EPSG:3857', width: Math.max(1, Math.floor(width * scale)), height: Math.max(1, Math.floor(height * scale)), transparent: true }, controller.signal).then(blob => {
        if (current !== generation || requestId !== imageGeneration || !owned(token)) return
        const element = image.getImage() as HTMLImageElement
        if (objectUrl) URL.revokeObjectURL(objectUrl)
        objectUrl = URL.createObjectURL(blob)
        element.addEventListener('load', () => { if (current === generation && requestId === imageGeneration && owned(token)) { loaded.value = true; loading.value = false } }, { once: true })
        element.addEventListener('error', () => { if (current === generation && requestId === imageGeneration) { error.value = '地图图像无法解码，请重新读取。'; loading.value = false } }, { once: true })
        element.src = objectUrl
      }).catch(failure => { if (current === generation && requestId === imageGeneration && !controller.signal.aborted) { error.value = describeIngestionError(failure).message; loading.value = false; layer.setSource(null) } })
    },
  })
  layer.setSource(source); fit()
}
async function download() {
  if (downloading.value) return
  const token = localStorage.getItem(TOKEN_KEY) || '', current = generation
  if (!owned(token)) return
  downloading.value = true; downloadAbort = new AbortController()
  try {
    const blob = await assetCoverageBlob(props.layerId, downloadAbort.signal)
    if (current !== generation || !owned(token)) return
    const url = URL.createObjectURL(blob), anchor = document.createElement('a'); anchor.href = url; anchor.download = `asset-${props.layerId}.tif`; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (failure) { if (current === generation) error.value = describeIngestionError(failure).message }
  finally { if (alive) downloading.value = false }
}
watch(() => props.layerId, refresh)
watch(() => auth.token, clear)
const storageChanged = (event: StorageEvent) => { if (event.key === TOKEN_KEY) clear() }
onMounted(() => { map = new Map({ target: target.value, layers: [layer], view: new View({ center: [0, 0], zoom: 2, projection: 'EPSG:3857' }) }); resize = new ResizeObserver(() => map?.updateSize()); if (target.value) resize.observe(target.value); window.addEventListener('storage', storageChanged); refresh() })
onBeforeUnmount(() => { clear(); alive = false; resize?.disconnect(); window.removeEventListener('storage', storageChanged); map?.setTarget(undefined); map?.dispose() })
</script>
<style scoped>
.asset-map-frame{border:1px solid #cad9cf;border-radius:9px;overflow:hidden;background:#f4f7f1;color:#263b30}.asset-map-frame header{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:20px 24px;background:#fff}.eyebrow{font-size:10px;color:#79917d;letter-spacing:2px}.asset-map-frame h3{font-size:17px;margin:8px 0;font-weight:600}.asset-map-frame p{font-size:11px;color:#788a7e;margin:0}.tools{display:flex;flex-wrap:wrap;gap:8px}.tools .el-button{margin:0}.asset-map{height:420px;background-color:#dfe8dd;background-image:linear-gradient(45deg,#eef3eb 25%,transparent 25%),linear-gradient(-45deg,#eef3eb 25%,transparent 25%),linear-gradient(45deg,transparent 75%,#eef3eb 75%),linear-gradient(-45deg,transparent 75%,#eef3eb 75%);background-size:24px 24px;background-position:0 0,0 12px,12px -12px,-12px 0}.map-status{padding:11px 20px;font-size:12px;color:#476b51;display:flex;justify-content:space-between;align-items:center;min-height:20px}.asset-map-frame footer{border-top:1px solid #dae4d7;padding:12px 20px;display:flex;justify-content:space-between;gap:12px;font-size:10px;color:#7c8e7c}@media(max-width:700px){.asset-map-frame header{align-items:flex-start;flex-direction:column}.asset-map{height:330px}.asset-map-frame footer{flex-direction:column}}
</style>
