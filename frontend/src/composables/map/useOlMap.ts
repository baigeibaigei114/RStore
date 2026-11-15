import { onBeforeUnmount, ref, shallowRef } from 'vue'
import Map from 'ol/Map'
import View from 'ol/View'
import TileLayer from 'ol/layer/Tile'
import TileState from 'ol/TileState'
import type ImageTile from 'ol/ImageTile'
import TileWMS from 'ol/source/TileWMS'
import XYZ from 'ol/source/XYZ'
import { defaults as defaultControls, FullScreen, ScaleLine } from 'ol/control'
import { fromLonLat, toLonLat } from 'ol/proj'
import type { Coordinate } from 'ol/coordinate'
import type { Extent } from 'ol/extent'

export interface UseOlMapOptions {
  center?: Coordinate
  zoom?: number
}

export interface WmsLayerOptions {
  url: string
  layers: string
  styles?: string
  format?: string
  transparent?: boolean
  opacity?: number
  authToken?: string
  extent?: Extent
}

const CHINA_BASE_TILE_URL =
  'https://webrd0{1-4}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}'

export function useOlMap(options: UseOlMapOptions = {}) {
  const map = shallowRef<Map>()
  const baseLayer = shallowRef<TileLayer<XYZ>>()
  let wmsLayers: TileLayer<TileWMS>[] = []
  const wmsFailed = ref(false)
  const pointerLonLat = ref<Coordinate | null>(null)
  const zoom = ref(options.zoom ?? 5)
  const ready = ref(false)

  function initMap(target: HTMLElement) {
    if (map.value) {
      map.value.setTarget(target)
      return map.value
    }

    baseLayer.value = new TileLayer({
      source: new XYZ({
        url: CHINA_BASE_TILE_URL,
        attributions: '地图底图 © 高德地图',
      }),
      zIndex: 0,
    })

    const view = new View({
      center: fromLonLat(options.center ?? [104, 35]),
      zoom: options.zoom ?? 5,
      minZoom: 2,
      maxZoom: 19,
    })

    map.value = new Map({
      target,
      layers: [baseLayer.value],
      view,
      controls: defaultControls().extend([new ScaleLine(), new FullScreen()]),
    })

    map.value.on('pointermove', (event) => {
      pointerLonLat.value = toLonLat(event.coordinate)
    })

    view.on('change:resolution', () => {
      zoom.value = Number(view.getZoom()?.toFixed(2) || 0)
    })

    ready.value = true
    return map.value
  }

  function createWmsLayer(options: WmsLayerOptions, index: number) {
    const layer = new TileLayer({
      source: new TileWMS({
        url: options.url,
        params: {
          LAYERS: options.layers,
          STYLES: options.styles || '',
          FORMAT: options.format || 'image/png',
          TRANSPARENT: options.transparent ?? true,
          TILED: true,
        },
        serverType: 'geoserver',
        crossOrigin: 'anonymous',
        tileLoadFunction: (tile, src) => {
          loadAuthorizedImageTile(tile as ImageTile, src, options.authToken)
        },
      }),
      opacity: options.opacity ?? 1,
      extent: options.extent,
      zIndex: 10 + index,
    })

    layer.getSource()?.on('tileloaderror', () => {
      if (wmsLayers.includes(layer)) wmsFailed.value = true
    })
    return layer
  }

  function setWmsLayers(options: WmsLayerOptions[]) {
    const currentMap = map.value
    if (!currentMap) return
    removeWmsLayer()
    wmsLayers = options.map(createWmsLayer)
    wmsLayers.forEach(layer => currentMap.addLayer(layer))
  }

  function removeWmsLayer() {
    wmsLayers.forEach(layer => map.value?.removeLayer(layer))
    wmsLayers = []
    wmsFailed.value = false
  }

  function updateWmsOpacity(opacity: number) {
    wmsLayers.forEach(layer => layer.setOpacity(opacity))
  }

  function setBaseLayerVisible(visible: boolean) {
    baseLayer.value?.setVisible(visible)
  }

  function resetView() {
    map.value?.getView().animate({
      center: fromLonLat(options.center ?? [104, 35]),
      zoom: options.zoom ?? 5,
      duration: 300,
    })
  }

  function destroyMap() {
    map.value?.setTarget(undefined)
    map.value = undefined
    baseLayer.value = undefined
    wmsLayers = []
    pointerLonLat.value = null
    ready.value = false
  }

  function loadAuthorizedImageTile(tile: ImageTile, src: string, authToken?: string) {
    const image = tile.getImage() as HTMLImageElement

    if (!authToken) {
      image.src = src
      return
    }

    fetch(src, {
      headers: {
        Authorization: `Bearer ${authToken}`,
      },
    })
      .then((response) => {
        if (!response.ok || !response.headers.get('content-type')?.startsWith('image/')) {
          throw new Error(`WMS 图层加载失败：${response.status}`)
        }
        return response.blob()
      })
      .then((blob) => {
        const objectUrl = URL.createObjectURL(blob)
        image.onload = () => URL.revokeObjectURL(objectUrl)
        image.onerror = () => URL.revokeObjectURL(objectUrl)
        image.src = objectUrl
      })
      .catch(() => {
        tile.setState(TileState.ERROR)
      })
  }

  onBeforeUnmount(() => {
    destroyMap()
  })

  return {
    map,
    pointerLonLat,
    zoom,
    ready,
    wmsFailed,
    initMap,
    setWmsLayers,
    removeWmsLayer,
    updateWmsOpacity,
    setBaseLayerVisible,
    resetView,
    destroyMap,
  }
}
