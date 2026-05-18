<template>
  <section>
    <div class="page-title">
      <span>空间查询</span>
      <h2>空间位置检索</h2>
      <p>支持地图矩形框选和行政区级联选择，空间相交计算仍由后端 PostGIS 完成。</p>
    </div>

    <div class="spatial-query-layout">
      <div class="spatial-map">
        <div ref="mapTarget" class="ol-map spatial-ol-map"></div>

        <el-card class="spatial-toolbar" shadow="never">
          <template #header>
            <div class="card-header">
              <span>检索条件</span>
              <el-tag :type="canSearch ? 'success' : 'info'" effect="plain">
                {{ canSearch ? '可检索' : '待选择' }}
              </el-tag>
            </div>
          </template>

          <el-form label-position="top" :model="filterForm">
            <el-form-item label="空间模式">
              <el-segmented v-model="spatialMode" :options="spatialModeOptions" class="full-width" />
            </el-form-item>

            <template v-if="spatialMode === 'bbox'">
              <div class="coordinate-line">
                <span>经度</span>
                <strong>{{ coordinateText[0] }}</strong>
              </div>
              <div class="coordinate-line">
                <span>纬度</span>
                <strong>{{ coordinateText[1] }}</strong>
              </div>
              <div class="bbox-box">{{ bbox || '点击“开始框选”，在地图上拖拽绘制矩形范围' }}</div>
            </template>

            <template v-else>
              <el-form-item label="省级行政区">
                <el-select
                  v-model="regionForm.provinceId"
                  filterable
                  clearable
                  class="full-width"
                  placeholder="请选择省级行政区"
                  :loading="topRegionLoading"
                  @change="handleProvinceChange"
                >
                  <el-option
                    v-for="item in provinceOptions"
                    :key="item.id"
                    :label="item.name"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>

              <el-form-item label="市级行政区">
                <el-select
                  v-model="regionForm.cityId"
                  filterable
                  clearable
                  class="full-width"
                  placeholder="请选择市级行政区"
                  :loading="cityLoading"
                  :disabled="cityOptions.length === 0"
                  @change="handleCityChange"
                >
                  <el-option
                    v-for="item in cityOptions"
                    :key="item.id"
                    :label="item.name"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>

              <el-form-item label="区县级行政区">
                <el-select
                  v-model="regionForm.districtId"
                  filterable
                  clearable
                  class="full-width"
                  placeholder="请选择区县级行政区"
                  :loading="districtLoading"
                  :disabled="districtOptions.length === 0"
                  @change="handleDistrictChange"
                >
                  <el-option
                    v-for="item in districtOptions"
                    :key="item.id"
                    :label="item.name"
                    :value="item.id"
                  />
                </el-select>
              </el-form-item>

              <el-alert
                v-if="selectedRegionName"
                type="success"
                :closable="false"
                show-icon
                class="region-alert"
              >
                <template #title>已选择：{{ selectedRegionName }}</template>
                <div>行政区 ID：{{ selectedRegionId }}</div>
              </el-alert>
              <el-alert
                v-else-if="spatialMode === 'region' && !topRegionLoading && provinceOptions.length === 0"
                type="warning"
                :closable="false"
                show-icon
                title="暂无顶级行政区数据"
              />
            </template>

            <el-divider />

            <el-form-item label="传感器">
              <el-input v-model="filterForm.sensor" clearable placeholder="例如 Sentinel-2" />
            </el-form-item>
            <el-form-item label="最大云量">
              <el-input-number
                v-model="filterForm.maxCloudPercent"
                :min="0"
                :max="100"
                :precision="2"
                :step="5"
                controls-position="right"
                class="full-width"
              />
            </el-form-item>
            <el-form-item label="采集时间">
              <el-date-picker
                v-model="filterForm.timeRange"
                type="datetimerange"
                start-placeholder="开始时间"
                end-placeholder="结束时间"
                value-format="YYYY-MM-DDTHH:mm:ssZ"
                class="full-width"
              />
            </el-form-item>
          </el-form>

          <div class="map-panel-actions spatial-actions">
            <el-button @click="handleClear">重置</el-button>
            <el-button v-if="spatialMode === 'bbox'" type="primary" @click="handleStartDraw">开始框选</el-button>
            <el-button type="success" :loading="loading || regionDetailLoading" :disabled="!canSearch" @click="fetchImages">
              检索
            </el-button>
          </div>
        </el-card>
      </div>

      <el-card class="query-result-panel" shadow="never">
        <template #header>
          <div class="card-header">
            <span>查询结果</span>
            <el-tag effect="plain">{{ pagination.total }} 条</el-tag>
          </div>
        </template>

        <el-empty v-if="!loading && imageList.length === 0" description="暂无查询结果" />

        <div v-else v-loading="loading" class="result-list">
          <div v-for="item in imageList" :key="item.id" class="result-item">
            <div class="result-item-title">
              <strong>{{ item.imageName }}</strong>
              <el-tag :type="item.visibility === 'PUBLIC' ? 'success' : 'info'" effect="plain" size="small">
                {{ item.visibility === 'PUBLIC' ? '公开' : '私有' }}
              </el-tag>
            </div>
            <div class="result-meta">
              <span>传感器：{{ item.sensorType || '未填写' }}</span>
              <span>云量：{{ formatCloud(item.cloudPercent) }}</span>
              <span>尺寸：{{ formatSize(item.width, item.height) }}</span>
              <span>状态：{{ imageStatusText(item.status) }}</span>
            </div>
            <div class="result-actions">
              <el-button link type="primary" @click="router.push(`/images/${item.id}`)">详情</el-button>
            </div>
          </div>
        </div>

        <div class="pagination-bar compact-pagination">
          <el-pagination
            v-model:current-page="pagination.pageNum"
            v-model:page-size="pagination.pageSize"
            small
            layout="prev, pager, next"
            :total="pagination.total"
            @current-change="fetchImages"
          />
        </div>
      </el-card>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { getRegionChildren, getRegionDetail } from '@/api/adminRegion'
import { getImageDetailApi, searchImagesApi, searchImagesByRegionApi } from '@/api/image'
import { useImageFootprintLayer } from '@/composables/map/useImageFootprintLayer'
import { useOlMap } from '@/composables/map/useOlMap'
import { useRegionBoundaryLayer } from '@/composables/map/useRegionBoundaryLayer'
import { useSpatialDraw } from '@/composables/map/useSpatialDraw'
import type { AdminRegion } from '@/types/adminRegion'
import type { ImageDetail, ImageListItem, ImageStatus } from '@/types/image'

type SpatialMode = 'bbox' | 'region'

interface FilterForm {
  sensor: string
  maxCloudPercent?: number
  timeRange: string[]
}

const router = useRouter()
const mapTarget = ref<HTMLElement>()
const loading = ref(false)
const imageList = ref<ImageListItem[]>([])
const spatialMode = ref<SpatialMode>('bbox')

const topRegionLoading = ref(false)
const cityLoading = ref(false)
const districtLoading = ref(false)
const regionDetailLoading = ref(false)
const provinceOptions = ref<AdminRegion[]>([])
const cityOptions = ref<AdminRegion[]>([])
const districtOptions = ref<AdminRegion[]>([])
const selectedRegionId = ref<number>()
const selectedRegionName = ref('')

const spatialModeOptions = [
  { label: '框选范围', value: 'bbox' },
  { label: '行政区', value: 'region' },
]

const filterForm = reactive<FilterForm>({
  sensor: '',
  maxCloudPercent: undefined,
  timeRange: [],
})

const regionForm = reactive({
  provinceId: undefined as number | undefined,
  cityId: undefined as number | undefined,
  districtId: undefined as number | undefined,
})

const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0,
})

const { pointerLonLat, initMap } = useOlMap({
  center: [104, 35],
  zoom: 5,
})

const {
  bbox,
  bindMap: bindDrawMap,
  startBoxDraw,
  clearDraw,
  destroyDraw,
} = useSpatialDraw()

const {
  bindMap: bindFootprintMap,
  renderFootprints,
  clearFootprints,
  destroyLayer,
} = useImageFootprintLayer()

const {
  bindMap: bindRegionMap,
  renderBoundary,
  clearBoundary,
  destroyLayer: destroyRegionLayer,
} = useRegionBoundaryLayer()

const coordinateText = computed(() => {
  if (!pointerLonLat.value) {
    return ['-', '-']
  }

  return [pointerLonLat.value[0].toFixed(6), pointerLonLat.value[1].toFixed(6)]
})

const canSearch = computed(() => {
  if (spatialMode.value === 'region') {
    return Boolean(selectedRegionId.value)
  }
  return Boolean(bbox.value)
})

onMounted(() => {
  if (mapTarget.value) {
    const createdMap = initMap(mapTarget.value)
    bindDrawMap(createdMap)
    bindFootprintMap(createdMap)
    bindRegionMap(createdMap)
  }

  loadTopRegions()
})

onBeforeUnmount(() => {
  destroyDraw()
  destroyLayer()
  destroyRegionLayer()
})

watch(spatialMode, (mode) => {
  imageList.value = []
  pagination.pageNum = 1
  pagination.total = 0
  clearFootprints()

  if (mode === 'region') {
    clearDraw()
    loadTopRegions()
    return
  }

  resetRegionSelection()
})

function handleStartDraw() {
  startBoxDraw(() => {
    pagination.pageNum = 1
    fetchImages()
  })
}

function handleClear() {
  clearDraw()
  clearFootprints()
  resetRegionSelection()
  filterForm.sensor = ''
  filterForm.maxCloudPercent = undefined
  filterForm.timeRange = []
  imageList.value = []
  pagination.pageNum = 1
  pagination.total = 0
}

async function loadTopRegions() {
  if (provinceOptions.value.length > 0 || topRegionLoading.value) {
    return
  }

  topRegionLoading.value = true
  try {
    provinceOptions.value = await getRegionChildren()
  } finally {
    topRegionLoading.value = false
  }
}

async function handleProvinceChange(regionId?: number) {
  regionForm.cityId = undefined
  regionForm.districtId = undefined
  cityOptions.value = []
  districtOptions.value = []
  clearSelectedRegion()

  if (!regionId) {
    return
  }

  cityLoading.value = true
  try {
    cityOptions.value = await getRegionChildren(regionId)
  } finally {
    cityLoading.value = false
  }
}

async function handleCityChange(regionId?: number) {
  regionForm.districtId = undefined
  districtOptions.value = []
  clearSelectedRegion()

  if (!regionId) {
    return
  }

  await selectRegion(regionId)

  districtLoading.value = true
  try {
    districtOptions.value = await getRegionChildren(regionId)
  } finally {
    districtLoading.value = false
  }
}

async function handleDistrictChange(regionId?: number) {
  clearSelectedRegion()

  if (!regionId) {
    return
  }

  await selectRegion(regionId)
}

async function selectRegion(regionId: number) {
  selectedRegionId.value = regionId
  regionDetailLoading.value = true

  try {
    const detail = await getRegionDetail(regionId)
    selectedRegionName.value = detail.name

    if (!detail.boundaryGeoJson) {
      ElMessage.warning('该行政区暂无边界数据，仍可按 regionId 检索')
      return
    }

    try {
      const drawn = renderBoundary(detail.boundaryGeoJson)
      if (!drawn) {
        ElMessage.warning('行政区边界为空，仍可按 regionId 检索')
      }
    } catch {
      clearBoundary()
      ElMessage.warning('行政区边界解析失败，仍可按 regionId 检索')
    }
  } finally {
    regionDetailLoading.value = false
  }
}

function clearSelectedRegion() {
  selectedRegionId.value = undefined
  selectedRegionName.value = ''
  clearBoundary()
}

function resetRegionSelection() {
  regionForm.provinceId = undefined
  regionForm.cityId = undefined
  regionForm.districtId = undefined
  cityOptions.value = []
  districtOptions.value = []
  clearSelectedRegion()
}

async function fetchImages() {
  if (spatialMode.value === 'region') {
    await fetchByRegion()
    return
  }

  await fetchByBbox()
}

async function fetchByBbox() {
  if (!bbox.value) {
    ElMessage.warning('请先在地图上框选查询范围')
    return
  }

  loading.value = true
  try {
    const page = await searchImagesApi({
      bbox: bbox.value,
      ...buildCommonParams(),
    })
    applyPageResult(page.records, page.total, page.pageNum, page.pageSize)
    await renderResultFootprints(page.records)
  } finally {
    loading.value = false
  }
}

async function fetchByRegion() {
  if (!selectedRegionId.value) {
    ElMessage.warning('请先选择行政区')
    return
  }

  loading.value = true
  try {
    const page = await searchImagesByRegionApi({
      regionId: selectedRegionId.value,
      ...buildCommonParams(),
    })
    applyPageResult(page.records, page.total, page.pageNum, page.pageSize)
    await renderResultFootprints(page.records)
  } finally {
    loading.value = false
  }
}

function buildCommonParams() {
  return {
    sensor: filterForm.sensor || undefined,
    maxCloudPercent: filterForm.maxCloudPercent,
    startTime: filterForm.timeRange?.[0],
    endTime: filterForm.timeRange?.[1],
    pageNum: pagination.pageNum,
    pageSize: pagination.pageSize,
  }
}

function applyPageResult(records: ImageListItem[], total: number, pageNum: number, pageSize: number) {
  imageList.value = records
  pagination.total = total
  pagination.pageNum = pageNum
  pagination.pageSize = pageSize
}

async function renderResultFootprints(records: ImageListItem[]) {
  const settled = await Promise.allSettled(records.map((item) => getImageDetailApi(item.id)))
  const details = settled
    .filter((item): item is PromiseFulfilledResult<ImageDetail> => item.status === 'fulfilled')
    .map((item) => item.value)

  renderFootprints(details)
}

function formatSize(width?: number, height?: number) {
  if (!width || !height) {
    return '未知'
  }
  return `${width} × ${height}`
}

function formatCloud(value?: number) {
  if (value === undefined || value === null) {
    return '未知'
  }
  return `${Number(value).toFixed(2)}%`
}

function imageStatusText(status: ImageStatus) {
  const map: Record<ImageStatus, string> = {
    UPLOADING: '上传中',
    PARSING: '解析中',
    READY: '可用',
    PROCESSING: '处理中',
    DELETE_LOCKED: '删除锁定',
    DELETED: '已删除',
    FAILED: '失败',
  }
  return map[status] || status
}
</script>
