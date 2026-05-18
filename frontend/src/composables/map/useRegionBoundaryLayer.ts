import { shallowRef } from 'vue'
import Map from 'ol/Map'
import GeoJSON from 'ol/format/GeoJSON'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import { Fill, Stroke, Style } from 'ol/style'

export function useRegionBoundaryLayer() {
  const map = shallowRef<Map>()
  const layer = shallowRef<VectorLayer<VectorSource>>()
  const geoJsonFormat = new GeoJSON()

  function bindMap(targetMap: Map) {
    map.value = targetMap

    if (!layer.value) {
      layer.value = new VectorLayer({
        source: new VectorSource(),
        style: new Style({
          stroke: new Stroke({
            color: '#f04438',
            width: 2,
          }),
          fill: new Fill({
            color: 'rgba(240, 68, 56, 0.16)',
          }),
        }),
        zIndex: 25,
      })
    }

    map.value.addLayer(layer.value)
  }

  function renderBoundary(boundaryGeoJson?: string) {
    const source = layer.value?.getSource()
    source?.clear()

    if (!boundaryGeoJson || !map.value) {
      return false
    }

    const geoJson = JSON.parse(boundaryGeoJson)
    const features = geoJsonFormat.readFeatures(geoJson, {
      dataProjection: 'EPSG:4326',
      featureProjection: 'EPSG:3857',
    })

    source?.addFeatures(features)

    const extent = source?.getExtent()
    if (extent && extent.every(Number.isFinite)) {
      map.value.getView().fit(extent, {
        padding: [48, 48, 48, 48],
        duration: 350,
        maxZoom: 13,
      })
    }

    return features.length > 0
  }

  function clearBoundary() {
    layer.value?.getSource()?.clear()
  }

  function destroyLayer() {
    if (map.value && layer.value) {
      map.value.removeLayer(layer.value)
    }
    layer.value = undefined
    map.value = undefined
  }

  return {
    bindMap,
    renderBoundary,
    clearBoundary,
    destroyLayer,
  }
}
