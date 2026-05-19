import { onBeforeUnmount, ref, shallowRef } from 'vue'
import Map from 'ol/Map'
import View from 'ol/View'
import TileLayer from 'ol/layer/Tile'
import TileWMS from 'ol/source/TileWMS'
import XYZ from 'ol/source/XYZ'
import { defaults as defaultControls, FullScreen, ScaleLine } from 'ol/control'
import { fromLonLat, toLonLat } from 'ol/proj'
import type { Coordinate } from 'ol/coordinate'

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
}

const CHINA_BASE_TILE_URL =
  'https://webrd0{1-4}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}'

export function useOlMap(options: UseOlMapOptions = {}) {
  const map = shallowRef<Map>()
  const baseLayer = shallowRef<TileLayer<XYZ>>()
  const wmsLayer = shallowRef<TileLayer<TileWMS>>()
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

  function addWmsLayer(options: WmsLayerOptions) {
    if (!map.value) {
      return
    }

    if (wmsLayer.value) {
      map.value.removeLayer(wmsLayer.value)
    }

    wmsLayer.value = new TileLayer({
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
      }),
      opacity: options.opacity ?? 0.75,
      zIndex: 10,
    })

    map.value.addLayer(wmsLayer.value)
  }

  function removeWmsLayer() {
    if (map.value && wmsLayer.value) {
      map.value.removeLayer(wmsLayer.value)
      wmsLayer.value = undefined
    }
  }

  function updateWmsOpacity(opacity: number) {
    wmsLayer.value?.setOpacity(opacity)
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
    wmsLayer.value = undefined
    pointerLonLat.value = null
    ready.value = false
  }

  onBeforeUnmount(() => {
    destroyMap()
  })

  return {
    map,
    pointerLonLat,
    zoom,
    ready,
    initMap,
    addWmsLayer,
    removeWmsLayer,
    updateWmsOpacity,
    resetView,
    destroyMap,
  }
}
