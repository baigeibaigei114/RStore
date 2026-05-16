import { shallowRef } from 'vue'
import Map from 'ol/Map'
import Feature from 'ol/Feature'
import WKT from 'ol/format/WKT'
import VectorLayer from 'ol/layer/Vector'
import VectorSource from 'ol/source/Vector'
import { Fill, Stroke, Style } from 'ol/style'

export interface FootprintItem {
  id: number
  imageName: string
  footprintWkt?: string
}

export function useImageFootprintLayer() {
  const map = shallowRef<Map>()
  const layer = shallowRef<VectorLayer<VectorSource>>()
  const wktFormat = new WKT()

  function bindMap(targetMap: Map) {
    map.value = targetMap

    if (!layer.value) {
      layer.value = new VectorLayer({
        source: new VectorSource(),
        style: new Style({
          stroke: new Stroke({
            color: '#1570ef',
            width: 2,
          }),
          fill: new Fill({
            color: 'rgba(21, 112, 239, 0.14)',
          }),
        }),
        zIndex: 30,
      })
    }

    map.value.addLayer(layer.value)
  }

  function renderFootprints(items: FootprintItem[]) {
    const source = layer.value?.getSource()
    source?.clear()

    items.forEach((item) => {
      if (!item.footprintWkt) {
        return
      }

      try {
        const feature = wktFormat.readFeature(item.footprintWkt, {
          dataProjection: 'EPSG:4326',
          featureProjection: 'EPSG:3857',
        }) as Feature
        feature.setProperties({
          imageId: item.id,
          imageName: item.imageName,
        })
        source?.addFeature(feature)
      } catch {
        // 单条 WKT 解析失败时跳过，不影响其它查询结果展示。
      }
    })
  }

  function clearFootprints() {
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
    renderFootprints,
    clearFootprints,
    destroyLayer,
  }
}
