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
/**
 * 地图浏览页面组件
 * 职责：
 *   - 初始化 OpenLayers 地图，显示高德底图和鼠标位置坐标
 *   - 查询并加载已发布到 GeoServer 的 WMS 图层
 *   - 支持手动输入 WMS 服务地址加载外部图层
 *   - 支持图层透明度调节和图层移除
 * 路由查询参数支持：?imageId=xxx&taskType=xxx 自动筛选图层
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useRoute } from 'vue-router'
import { getLayerProxyWmsUrl, listLayersApi } from '@/api/layer'
import { TOKEN_KEY } from '@/api/request'
import { useOlMap } from '@/composables/map/useOlMap'
import type { LayerListItem, LayerSearchParams } from '@/types/layer'

/** 路由实例，用于读取查询参数 */
const route = useRoute()

/* ==================== 地图相关 ==================== */
/** 地图容器 DOM 元素引用 */
const mapTarget = ref<HTMLElement>()

/* ==================== WMS 透明度 ==================== */
/** WMS 图层透明度百分比（0-100），默认 75% */
const wmsOpacityPercent = ref(75)
/** 标记当前是否有已加载的 WMS 图层 */
const hasWmsLayer = ref(false)

/* ==================== 图层列表与选择 ==================== */
/** 加载图层列表时的 loading 状态 */
const layerLoading = ref(false)
/** 当前选中的图层 ID */
const selectedLayerId = ref<number>()
/** 从 API 获取的已发布图层列表 */
const layers = ref<LayerListItem[]>([])
/** 查询结果总数 */
const layerTotal = ref(0)

/** 手动输入 WMS 服务地址的表单数据 */
const wmsForm = reactive({
  /** WMS 服务地址 */
  url: '',
  /** 图层名称 */
  layers: '',
})

/** 图层列表查询条件表单 */
const layerQuery = reactive<LayerSearchParams>({
  pageNum: 1,
  pageSize: 20,
  taskType: '',
  imageId: undefined,
  keyword: '',
})

/**
 * 从 useOlMap 组合式函数中解构地图操作方法
 * - pointerLonLat: 鼠标所在位置的经纬度坐标（响应式）
 * - zoom: 当前缩放级别（响应式）
 * - ready: 地图是否已初始化完成
 * - initMap: 初始化地图
 * - addWmsLayer: 添加 WMS 图层
 * - removeWmsLayer: 移除 WMS 图层
 * - updateWmsOpacity: 更新 WMS 图层透明度
 * - resetView: 重置视图到全国范围
 */
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

/** 鼠标位置格式化文本，保留 6 位小数，无坐标时显示 "-" */
const coordinateText = computed(() => {
  if (!pointerLonLat.value) {
    return ['-', '-']
  }

  return [pointerLonLat.value[0].toFixed(6), pointerLonLat.value[1].toFixed(6)]
})

/** 根据选中 ID 从图层列表中查找对应的图层对象 */
const selectedLayer = computed(() => layers.value.find((layer) => layer.id === selectedLayerId.value))

onMounted(async () => {
  /** 从路由查询参数中恢复图层查询条件 */
  applyRouteLayerQuery()

  /** 初始化地图 */
  if (mapTarget.value) {
    initMap(mapTarget.value)
  }

  /** 加载已发布图层列表 */
  await fetchLayers()
})

/**
 * 手动添加 WMS 图层
 * 验证 URL 和图层名称不为空后，通过 useOlMap 的 addWmsLayer 方法加载
 * 若地址以 /api/ 开头则自动携带认证令牌
 */
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

/**
 * 从 API 获取已发布图层列表
 * 更新图层列表和总数，维护选中项的有效性
 */
async function fetchLayers() {
  layerLoading.value = true
  try {
    const result = await listLayersApi(layerQuery)
    layers.value = result.records || []
    layerTotal.value = result.total || 0

    /** 若上一轮选中的图层已不在当前页结果中，清空选中 */
    if (selectedLayerId.value && !layers.value.some((layer) => layer.id === selectedLayerId.value)) {
      selectedLayerId.value = undefined
    }

    /** 默认选中列表第一个图层 */
    if (!selectedLayerId.value && layers.value.length > 0) {
      selectedLayerId.value = layers.value[0].id
    }
  } finally {
    layerLoading.value = false
  }
}

/** 查询图层：重置到第一页并重新请求 */
function handleLayerSearch() {
  layerQuery.pageNum = 1
  fetchLayers()
}

/**
 * 加载选中的已发布图层到地图上
 * 使用图层的代理 WMS URL 和完全限定名称作为 WMS 参数
 */
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

/** 移除地图上当前显示的 WMS 图层 */
function removeWmsLayer() {
  removeLayer()
  hasWmsLayer.value = false
}

/**
 * 透明度滑块变化时的回调处理
 * 将 0-100 的百分比转换为 0-1 的小数并更新图层透明度
 */
function handleOpacityChange(value: number | number[]) {
  const opacity = Array.isArray(value) ? value[0] : value
  updateWmsOpacity(opacity / 100)
}

/**
 * 从路由查询参数中提取初始图层过滤条件
 * 支持参数：imageId（影像 ID）和 taskType（任务类型）
 * 用于从任务详情页跳转到地图页时自动定位到相关图层
 */
function applyRouteLayerQuery() {
  const imageId = Number(route.query.imageId)
  if (Number.isFinite(imageId) && imageId > 0) {
    layerQuery.imageId = imageId
  }

  if (typeof route.query.taskType === 'string') {
    layerQuery.taskType = route.query.taskType
  }
}

/**
 * 获取当前登录令牌
 * @returns 令牌字符串或 undefined（未登录时）
 */
function currentToken() {
  return localStorage.getItem(TOKEN_KEY) || undefined
}
</script>
