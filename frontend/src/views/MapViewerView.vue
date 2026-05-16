<template>
  <section>
    <div class="page-title">
      <span>地图浏览</span>
      <h2>地图与图层</h2>
      <p>当前页面已封装基础地图能力，后续可叠加影像范围、空间查询结果和 GeoServer 发布图层。</p>
    </div>

    <div class="map-viewer">
      <div ref="mapTarget" class="ol-map"></div>

      <el-card class="map-panel" shadow="never">
        <template #header>
          <div class="card-header">
            <span>地图状态</span>
            <el-tag :type="ready ? 'success' : 'info'" effect="plain">
              {{ ready ? '已加载' : '加载中' }}
            </el-tag>
          </div>
        </template>

        <el-descriptions :column="1" border>
          <el-descriptions-item label="经度">{{ coordinateText[0] }}</el-descriptions-item>
          <el-descriptions-item label="纬度">{{ coordinateText[1] }}</el-descriptions-item>
          <el-descriptions-item label="缩放">{{ zoom }}</el-descriptions-item>
        </el-descriptions>

        <div class="map-panel-actions">
          <el-button :icon="Refresh" @click="resetView">回到全国</el-button>
        </div>
      </el-card>

      <el-card class="map-layer-panel" shadow="never">
        <template #header>
          <div class="card-header">
            <span>WMS 图层</span>
            <el-tag size="small">预留</el-tag>
          </div>
        </template>

        <el-form label-position="top" :model="wmsForm">
          <el-form-item label="服务地址">
            <el-input v-model="wmsForm.url" placeholder="http://localhost:8081/geoserver/workspace/wms" />
          </el-form-item>
          <el-form-item label="图层名称">
            <el-input v-model="wmsForm.layers" placeholder="workspace:layer_name" />
          </el-form-item>
          <el-form-item label="透明度">
            <el-slider v-model="wmsOpacityPercent" :min="0" :max="100" @input="handleOpacityChange" />
          </el-form-item>
          <div class="map-panel-actions">
            <el-button :disabled="!hasWmsLayer" @click="removeWmsLayer">移除</el-button>
            <el-button type="primary" @click="handleAddWmsLayer">加载</el-button>
          </div>
        </el-form>
      </el-card>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useOlMap } from '@/composables/map/useOlMap'

const mapTarget = ref<HTMLElement>()
const wmsOpacityPercent = ref(75)
const hasWmsLayer = ref(false)

const wmsForm = reactive({
  url: 'http://localhost:8081/geoserver/remote_sensing/wms',
  layers: '',
})

const {
  pointerLonLat,
  zoom,
  ready,
  initMap,
  addWmsLayer,
  removeWmsLayer: removeLayer,
  updateWmsOpacity,
  resetView,
} = useOlMap({
  center: [104, 35],
  zoom: 5,
})

const coordinateText = computed(() => {
  if (!pointerLonLat.value) {
    return ['-', '-']
  }

  return [pointerLonLat.value[0].toFixed(6), pointerLonLat.value[1].toFixed(6)]
})

onMounted(() => {
  if (mapTarget.value) {
    initMap(mapTarget.value)
  }
})

function handleAddWmsLayer() {
  if (!wmsForm.url || !wmsForm.layers) {
    ElMessage.warning('请填写 WMS 服务地址和图层名称')
    return
  }

  addWmsLayer({
    url: wmsForm.url,
    layers: wmsForm.layers,
    opacity: wmsOpacityPercent.value / 100,
  })
  hasWmsLayer.value = true
  ElMessage.success('WMS 图层已加载')
}

function removeWmsLayer() {
  removeLayer()
  hasWmsLayer.value = false
}

function handleOpacityChange(value: number | number[]) {
  const opacity = Array.isArray(value) ? value[0] : value
  updateWmsOpacity(opacity / 100)
}
</script>
