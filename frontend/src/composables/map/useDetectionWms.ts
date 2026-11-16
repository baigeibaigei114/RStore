import { ref } from 'vue'
import type Map from 'ol/Map'
import type { Extent } from 'ol/extent'
import { getHeight, getIntersection, getWidth, isEmpty } from 'ol/extent'
import ImageLayer from 'ol/layer/Image'
import ImageSource from 'ol/source/Image'
import { unByKey } from 'ol/Observable'
import { getLayerProxyWmsUrl } from '@/api/layer'
import { TOKEN_KEY } from '@/api/request'
import type { LayerListItem } from '@/types/layer'

export type DetectionPeriod = 'before' | 'after' | 'overlay'

export function useDetectionWms() {
  const failed = ref<DetectionPeriod[]>([])
  const pending = ref(false)
  let map: Map | undefined
  let result: LayerListItem | undefined
  let footprint: Extent | undefined
  let cancelEvents: (() => void) | undefined
  let request: AbortController | undefined
  let requestedKey = ''
  let firstViewport: string | undefined
  let opacity = 1
  let period: DetectionPeriod = 'overlay'
  const rendered = new globalThis.Map<DetectionPeriod, { layer: ImageLayer<ImageSource>; key: string }>()

  function remove(part: DetectionPeriod) {
    const existing = rendered.get(part)
    if (existing) map?.removeLayer(existing.layer)
    rendered.delete(part)
  }

  function cancel() {
    request?.abort()
    request = undefined
    requestedKey = ''
    pending.value = false
  }

  function clear() {
    cancel()
    cancelEvents?.()
    cancelEvents = undefined
    for (const part of rendered.keys()) remove(part)
    failed.value = []
    map = undefined
    result = undefined
    footprint = undefined
  }

  function start(currentMap: Map, currentResult: LayerListItem, extent: Extent, selected: DetectionPeriod) {
    clear()
    map = currentMap
    result = currentResult
    footprint = extent
    period = selected
    firstViewport = undefined
    const keys = [
      map.on('movestart', () => {
        const view = currentMap.getView()
        if (view.getInteracting() || view.getAnimating()) cancel()
      }),
      map.on('moveend', () => load()),
      map.on('change:size', () => load()),
    ]
    cancelEvents = () => unByKey(keys)
    load()
  }

  function select(selected: DetectionPeriod, retry = false) {
    if (selected !== period) cancel()
    period = selected
    load(retry)
  }

  function setOpacity(value: number) {
    opacity = value
    rendered.forEach(({ layer }) => layer.setOpacity(value))
  }

  function load(retry = false) {
    const currentMap = map, currentResult = result, size = map?.getSize(), token = localStorage.getItem(TOKEN_KEY)
    if (!currentMap || !currentResult || !footprint || !size?.[0] || !size[1] || !token) return
    const view = currentMap.getView()
    if (view.getInteracting() || view.getAnimating()) return
    const extent = getIntersection(view.calculateExtent(size), footprint)
    if (isEmpty(extent) || getWidth(extent) <= 0 || getHeight(extent) <= 0) {
      cancel()
      failed.value = []
      return
    }
    const projection = view.getProjection().getCode()
    const resolution = view.getResolution()!
    const width = getWidth(extent) / resolution
    const height = getHeight(extent) / resolution
    const bbox = extent.map(coordinate => coordinate.toFixed(4)).join(',')
    const viewport = [bbox, resolution].join('|')
    firstViewport ??= viewport
    const initial = viewport === firstViewport
    // Use CSS pixels for the first viewport, including retries and layer switches.
    const pixelRatio = Math.min(initial ? 1 : (window.devicePixelRatio || 1), (initial ? 1024 : 2048) / Math.max(width, height))
    const parts: DetectionPeriod[] = period === 'overlay'
      ? (currentResult.hasPredictedChanges === false ? ['after'] : ['after', 'overlay'])
      : [period]
    const requestWidth = Math.max(1, Math.round(width * pixelRatio))
    const requestHeight = Math.max(1, Math.round(height * pixelRatio))
    const key = [projection, bbox, requestWidth, requestHeight].join('|')
    const nextRequest = [period, key].join('|')
    if (!retry && requestedKey === nextRequest) return
    cancel()
    requestedKey = nextRequest
    for (const part of rendered.keys()) if (!parts.includes(part)) remove(part)
    const needed = parts.filter(part => rendered.get(part)?.key !== key)
    failed.value = []
    if (!needed.length) return
    const controller = new AbortController()
    request = controller
    pending.value = true
    const active = () => request === controller && !controller.signal.aborted && token === localStorage.getItem(TOKEN_KEY)
    const hasAfter = rendered.has('after')
    const render = (part: DetectionPeriod, image: HTMLImageElement) => {
      const source = new ImageSource({ projection, loader: () => ({ image, extent, pixelRatio: 1 }) })
      const layer = new ImageLayer({ source, extent, zIndex: part === 'overlay' ? 11 : 10, opacity })
      remove(part)
      currentMap.addLayer(layer)
      rendered.set(part, { layer, key })
    }
    const jobs = needed.map(async part => {
      const png = part === 'overlay' || currentResult.opaqueRgb !== true
      const url = new URL(getLayerProxyWmsUrl(currentResult), window.location.href)
      const params = {
        SERVICE: 'WMS', REQUEST: 'GetMap', VERSION: '1.1.1', SRS: projection,
        BBOX: bbox, WIDTH: String(requestWidth), HEIGHT: String(requestHeight), period: part,
        FORMAT: png ? 'image/png' : 'image/jpeg', TRANSPARENT: String(png), TILED: 'false',
      }
      Object.entries(params).forEach(([name, value]) => url.searchParams.set(name, value))
      try {
        const image = await fetchImage(url, token, controller.signal)
        if (active() && part !== 'overlay' && !hasAfter) render(part, image)
        return { part, image }
      } catch {
        return { part }
      }
    })
    void Promise.all(jobs).then(images => {
      if (!active()) return
      const errors = images.filter(item => !item.image).map(item => item.part)
      for (const { part, image } of images) {
        if (image && (part !== 'overlay' || !errors.includes('after')) && rendered.get(part)?.key !== key) render(part, image)
      }
      if (errors.includes('overlay')) remove('overlay')
      failed.value = errors
      pending.value = false
    })
  }

  return { start, clear, select, setOpacity, failed, pending }
}

async function fetchImage(url: URL, token: string, signal: AbortSignal) {
  const response = await fetch(url, { headers: { Authorization: `Bearer ${token}` }, signal })
  if (!response.ok || !response.headers.get('content-type')?.startsWith('image/')) {
    throw new Error(`WMS ${response.status}`)
  }
  const objectUrl = URL.createObjectURL(await response.blob())
  const image = new Image()
  try {
    image.src = objectUrl
    await image.decode()
    return image
  } finally {
    URL.revokeObjectURL(objectUrl)
  }
}
