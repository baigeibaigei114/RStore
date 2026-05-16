import { ref, shallowRef } from 'vue'
import Map from 'ol/Map'
import Draw, { createBox } from 'ol/interaction/Draw'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import { Fill, Stroke, Style } from 'ol/style'
import { transformExtent } from 'ol/proj'
import type { Extent } from 'ol/extent'

export function useSpatialDraw() {
  const map = shallowRef<Map>()
  const drawLayer = shallowRef<VectorLayer<VectorSource>>()
  const drawInteraction = shallowRef<Draw>()
  const bbox = ref('')
  const extentLonLat = ref<Extent | null>(null)

  function bindMap(targetMap: Map) {
    map.value = targetMap

    if (!drawLayer.value) {
      drawLayer.value = new VectorLayer({
        source: new VectorSource(),
        style: new Style({
          stroke: new Stroke({
            color: '#12b76a',
            width: 2,
          }),
          fill: new Fill({
            color: 'rgba(18, 183, 106, 0.16)',
          }),
        }),
        zIndex: 20,
      })
    }

    map.value.addLayer(drawLayer.value)
  }

  function startBoxDraw(onDrawEnd?: (bbox: string, extent: Extent) => void) {
    if (!map.value || !drawLayer.value) {
      return
    }

    stopDraw()
    clearDraw()

    drawInteraction.value = new Draw({
      source: drawLayer.value.getSource() || undefined,
      type: 'Circle',
      geometryFunction: createBox(),
    })

    drawInteraction.value.once('drawend', (event) => {
      const extent = event.feature.getGeometry()?.getExtent()

      if (extent) {
        const lonLatExtent = transformExtent(extent, 'EPSG:3857', 'EPSG:4326')
        extentLonLat.value = lonLatExtent
        bbox.value = lonLatExtent.map((item) => item.toFixed(6)).join(',')
        onDrawEnd?.(bbox.value, lonLatExtent)
      }

      stopDraw()
    })

    map.value.addInteraction(drawInteraction.value)
  }

  function stopDraw() {
    if (map.value && drawInteraction.value) {
      map.value.removeInteraction(drawInteraction.value)
      drawInteraction.value = undefined
    }
  }

  function clearDraw() {
    drawLayer.value?.getSource()?.clear()
    bbox.value = ''
    extentLonLat.value = null
  }

  function destroyDraw() {
    stopDraw()
    if (map.value && drawLayer.value) {
      map.value.removeLayer(drawLayer.value)
    }
    drawLayer.value = undefined
    map.value = undefined
  }

  return {
    bbox,
    extentLonLat,
    bindMap,
    startBoxDraw,
    stopDraw,
    clearDraw,
    destroyDraw,
  }
}
