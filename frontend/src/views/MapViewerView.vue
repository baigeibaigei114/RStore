<template>
  <section>
    <div class="page-title">
      <span>地图浏览</span>
      <h2>地图与图层</h2>
      <p>定位监测影像，查看分析结果；关闭底图可避免道路与地名干扰。</p>
    </div>

    <div class="map-viewer">
      <div ref="mapTarget" class="ol-map"></div>

      <div class="map-toolbar">
        <el-button :disabled="!loadedLayer" :loading="locating" @click="locateLoadedLayer">定位当前影像</el-button>
        <el-button :aria-expanded="panelOpen" aria-controls="map-controls" @click="panelOpen = !panelOpen">
          {{ panelOpen ? '收起面板' : '展开面板' }}
        </el-button>
      </div>

      <aside v-show="panelOpen" id="map-controls" class="map-controls" aria-label="地图与图层控制">
      <MonitoringRegionPanel :map="map" />
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
          <el-form-item :label="`影像不透明度 · ${wmsOpacityPercent}%`">
            <el-slider v-model="wmsOpacityPercent" :min="0" :max="100" @input="handleOpacityChange" />
          </el-form-item>
          <el-form-item label="显示地图底图">
            <el-switch v-model="baseMapVisible" @change="setBaseLayerVisible(baseMapVisible)" />
            <span class="display-hint">关闭后使用纯色背景，不改变影像数据</span>
          </el-form-item>
          <div class="map-panel-actions">
            <el-button :disabled="!hasWmsLayer" @click="removeWmsLayer">移除</el-button>
            <el-button type="primary" @click="handleAddWmsLayer">加载</el-button>
          </div>
        </el-form>
      </el-card>
      </aside>
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
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import WKT from 'ol/format/WKT'
import MonitoringRegionPanel from '@/components/MonitoringRegionPanel.vue'
import { getImageDetailApi } from '@/api/image'
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
/** 影像默认完全不透明；底图默认显示，可手动关闭。 */
const wmsOpacityPercent = ref(100)
const baseMapVisible = ref(true)
const panelOpen = ref(true)
const loadedLayer = ref<LayerListItem>()
const locating = ref(false)
let locationRequest = 0
let resizeObserver: ResizeObserver | undefined
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
  map,
  pointerLonLat,
  zoom,
  ready,
  initMap,
  addWmsLayer,
  removeWmsLayer: removeLayer,
  updateWmsOpacity,
  setBaseLayerVisible,
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
    setBaseLayerVisible(baseMapVisible.value)
    resizeObserver = new ResizeObserver(() => map.value?.updateSize())
    resizeObserver.observe(mapTarget.value)
  }

  /** 加载已发布图层列表 */
  await fetchLayers()
})

onBeforeUnmount(() => {
  locationRequest++
  resizeObserver?.disconnect()
})

async function locateLoadedLayer() {
  const layer = loadedLayer.value
  if (!layer) return
  const requestId = ++locationRequest
  locating.value = true
  try {
    const image = await getImageDetailApi(layer.imageId)
    if (requestId !== locationRequest) return
    if (!image.footprintWkt) {
      ElMessage.warning('该影像缺少空间范围，暂时无法定位')
      return
    }
    const geometry = new WKT().readGeometry(image.footprintWkt, {
      dataProjection: 'EPSG:4326',
      featureProjection: 'EPSG:3857',
    })
    const extent = geometry.getExtent()
    if (!extent.every(Number.isFinite) || extent[0] >= extent[2] || extent[1] >= extent[3]) {
      ElMessage.warning('影像空间范围无效，暂时无法定位')
      return
    }
    const width = mapTarget.value?.clientWidth ?? 0
    const rightPadding = panelOpen.value && width > 760 ? 400 : 40
    map.value?.getView().fit(extent, {
      padding: [70, rightPadding, 40, 40],
      maxZoom: 17,
      duration: 450,
    })
  } catch {
    if (requestId === locationRequest) ElMessage.warning('无法读取影像范围，请检查权限或稍后重试；图层仍可浏览')
  } finally {
    if (requestId === locationRequest) locating.value = false
  }
}

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

  locationRequest++
  locating.value = false
  loadedLayer.value = undefined
  addWmsLayer({
    url: wmsForm.url,
    layers: wmsForm.layers,
    opacity: wmsOpacityPercent.value / 100,
    authToken: wmsForm.url.startsWith('/api/') ? currentToken() : undefined,
  })
  hasWmsLayer.value = true
  ElMessage.success('已添加 WMS 图层；手动服务暂不支持自动定位')
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
  loadedLayer.value = { ...selectedLayer.value }
  void locateLoadedLayer()
  ElMessage.success(`已添加图层：${qualifiedLayerName}`)
}

/** 移除地图上当前显示的 WMS 图层 */
function removeWmsLayer() {
  locationRequest++
  locating.value = false
  loadedLayer.value = undefined
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

<style scoped>
.map-viewer {
  height: clamp(580px, 76vh, 900px);
  min-height: 580px;
  background: #e9edf0;
}

.ol-map {
  height: 100%;
  min-height: 0;
}

.map-toolbar {
  position: absolute;
  top: 14px;
  right: 52px;
  z-index: 3;
  display: flex;
  gap: 8px;
}

.map-toolbar .el-button + .el-button {
  margin-left: 0;
}

.map-controls {
  position: absolute;
  top: 62px;
  right: 14px;
  bottom: 32px;
  width: 350px;
  z-index: 2;
  overflow-y: auto;
  overscroll-behavior: contain;
  border: 1px solid #d0d5dd;
  border-radius: 10px;
  background: #fff;
  box-shadow: 0 8px 24px #10182818;
}

.map-panel,
.map-layer-panel {
  position: static;
  width: auto;
  margin: 0;
  border: 0;
  border-radius: 0;
}

.map-layer-panel {
  border-top: 1px solid #e4e7ec;
}

.map-panel-actions {
  flex-wrap: wrap;
}

.display-hint {
  margin-left: 10px;
  color: #667085;
  font-size: 12px;
}

@media (max-width: 760px) {
  .map-controls {
    top: auto;
    left: 12px;
    right: 12px;
    bottom: 30px;
    width: auto;
    max-height: 44%;
  }
}
</style>
