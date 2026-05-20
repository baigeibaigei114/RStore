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
            <el-tag size="small" type="success" effect="plain">已对接</el-tag>
          </div>
        </template>

        <el-form label-position="top" :model="layerQuery">
          <el-form-item label="已发布图层">
            <el-select
              v-model="selectedLayerId"
              class="full-width"
              :loading="layerLoading"
              placeholder="请选择后端发布图层"
              clearable
            >
              <el-option
                v-for="layer in layers"
                :key="layer.id"
                :label="layer.qualifiedLayerName || layer.layerName || `图层 ${layer.id}`"
                :value="layer.id"
              >
                <div class="layer-option">
                  <span>{{ layer.qualifiedLayerName || layer.layerName || `图层 ${layer.id}` }}</span>
                  <small>{{ layer.imageName || layer.taskName || '未命名结果' }}</small>
                </div>
              </el-option>
            </el-select>
          </el-form-item>

          <el-row :gutter="8">
            <el-col :span="12">
              <el-form-item label="任务类型">
                <el-select v-model="layerQuery.taskType" clearable placeholder="全部">
                  <el-option label="NDVI" value="NDVI" />
                  <el-option label="NDWI" value="NDWI" />
                  <el-option label="变化检测" value="CHANGE_DETECTION" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="影像 ID">
                <el-input-number v-model="layerQuery.imageId" :min="1" controls-position="right" class="full-width" />
              </el-form-item>
            </el-col>
          </el-row>

          <el-form-item label="关键字">
            <el-input v-model="layerQuery.keyword" clearable placeholder="影像名、任务名或图层名" @keyup.enter="handleLayerSearch" />
          </el-form-item>

          <div class="map-panel-actions">
            <span class="layer-count">共 {{ layerTotal }} 个</span>
            <el-button :loading="layerLoading" @click="handleLayerSearch">查询图层</el-button>
            <el-button type="primary" :disabled="!selectedLayer" @click="handleLoadSelectedLayer">
              加载选中图层
            </el-button>
          </div>
        </el-form>

        <el-divider />

        <el-form label-position="top" :model="wmsForm">
          <el-form-item label="服务地址">
            <el-input v-model="wmsForm.url" placeholder="/api/layers/{id}/wms 或 GeoServer WMS 地址" />
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
import { useRoute } from 'vue-router'
import { getLayerProxyWmsUrl, listLayersApi } from '@/api/layer'
import { TOKEN_KEY } from '@/api/request'
import { useOlMap } from '@/composables/map/useOlMap'
import type { LayerListItem, LayerSearchParams } from '@/types/layer'

const route = useRoute()
const mapTarget = ref<HTMLElement>()
const wmsOpacityPercent = ref(75)
const hasWmsLayer = ref(false)
const layerLoading = ref(false)
const selectedLayerId = ref<number>()
const layers = ref<LayerListItem[]>([])
const layerTotal = ref(0)

const wmsForm = reactive({
  url: '',
  layers: '',
})

const layerQuery = reactive<LayerSearchParams>({
  pageNum: 1,
  pageSize: 20,
  taskType: '',
  imageId: undefined,
  keyword: '',
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

const selectedLayer = computed(() => layers.value.find((layer) => layer.id === selectedLayerId.value))

onMounted(async () => {
  applyRouteLayerQuery()

  if (mapTarget.value) {
    initMap(mapTarget.value)
  }

  await fetchLayers()
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
    authToken: wmsForm.url.startsWith('/api/') ? currentToken() : undefined,
  })
  hasWmsLayer.value = true
  ElMessage.success('WMS 图层已加载')
}

async function fetchLayers() {
  layerLoading.value = true
  try {
    const result = await listLayersApi(layerQuery)
    layers.value = result.records || []
    layerTotal.value = result.total || 0

    if (selectedLayerId.value && !layers.value.some((layer) => layer.id === selectedLayerId.value)) {
      selectedLayerId.value = undefined
    }

    if (!selectedLayerId.value && layers.value.length > 0) {
      selectedLayerId.value = layers.value[0].id
    }
  } finally {
    layerLoading.value = false
  }
}

function handleLayerSearch() {
  layerQuery.pageNum = 1
  fetchLayers()
}

function handleLoadSelectedLayer() {
  if (!selectedLayer.value) {
    ElMessage.warning('请先选择一个已发布图层')
    return
  }

  const proxyWmsUrl = getLayerProxyWmsUrl(selectedLayer.value)
  const qualifiedLayerName = selectedLayer.value.qualifiedLayerName || selectedLayer.value.layerName || String(selectedLayer.value.id)

  wmsForm.url = proxyWmsUrl
  wmsForm.layers = qualifiedLayerName

  addWmsLayer({
    url: proxyWmsUrl,
    layers: qualifiedLayerName,
    opacity: wmsOpacityPercent.value / 100,
    authToken: currentToken(),
  })

  hasWmsLayer.value = true
  ElMessage.success(`已加载图层：${qualifiedLayerName}`)
}

function removeWmsLayer() {
  removeLayer()
  hasWmsLayer.value = false
}

function handleOpacityChange(value: number | number[]) {
  const opacity = Array.isArray(value) ? value[0] : value
  updateWmsOpacity(opacity / 100)
}

function applyRouteLayerQuery() {
  const imageId = Number(route.query.imageId)
  if (Number.isFinite(imageId) && imageId > 0) {
    layerQuery.imageId = imageId
  }

  if (typeof route.query.taskType === 'string') {
    layerQuery.taskType = route.query.taskType
  }
}

function currentToken() {
  return localStorage.getItem(TOKEN_KEY) || undefined
}
</script>
