<template>
  <section>
    <div class="page-title">
      <span>空间查询</span>
      <h2>地图框选查询</h2>
      <p>在地图上绘制矩形范围，前端会转换为 bbox 参数并调用后端空间检索接口。</p>
    </div>

    <div class="spatial-query-layout">
      <div class="spatial-map">
        <div ref="mapTarget" class="ol-map spatial-ol-map"></div>

        <el-card class="spatial-toolbar" shadow="never">
          <template #header>
            <div class="card-header">
              <span>框选工具</span>
              <el-tag :type="bbox ? 'success' : 'info'" effect="plain">
                {{ bbox ? '已选择范围' : '未选择范围' }}
              </el-tag>
            </div>
          </template>

          <div class="coordinate-line">
            <span>经度</span>
            <strong>{{ coordinateText[0] }}</strong>
          </div>
          <div class="coordinate-line">
            <span>纬度</span>
            <strong>{{ coordinateText[1] }}</strong>
          </div>
          <div class="bbox-box">{{ bbox || '请点击“开始框选”，在地图上拖拽绘制矩形范围' }}</div>

          <div class="map-panel-actions">
            <el-button @click="handleClear">清除</el-button>
            <el-button type="primary" @click="handleStartDraw">开始框选</el-button>
            <el-button type="success" :loading="loading" :disabled="!bbox" @click="fetchByBbox">
              查询
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
            @current-change="fetchByBbox"
          />
        </div>
      </el-card>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { getImageDetailApi, searchImagesApi } from '@/api/image'
import { useImageFootprintLayer } from '@/composables/map/useImageFootprintLayer'
import { useOlMap } from '@/composables/map/useOlMap'
import { useSpatialDraw } from '@/composables/map/useSpatialDraw'
import type { ImageDetail, ImageListItem, ImageStatus } from '@/types/image'

const router = useRouter()
const mapTarget = ref<HTMLElement>()
const loading = ref(false)
const imageList = ref<ImageListItem[]>([])

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

const coordinateText = computed(() => {
  if (!pointerLonLat.value) {
    return ['-', '-']
  }

  return [pointerLonLat.value[0].toFixed(6), pointerLonLat.value[1].toFixed(6)]
})

onMounted(() => {
  if (!mapTarget.value) {
    return
  }

  const createdMap = initMap(mapTarget.value)
  bindDrawMap(createdMap)
  bindFootprintMap(createdMap)
})

onBeforeUnmount(() => {
  destroyDraw()
  destroyLayer()
})

function handleStartDraw() {
  startBoxDraw(() => {
    pagination.pageNum = 1
    fetchByBbox()
  })
}

function handleClear() {
  clearDraw()
  clearFootprints()
  imageList.value = []
  pagination.pageNum = 1
  pagination.total = 0
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
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
    })
    imageList.value = page.records
    pagination.total = page.total
    pagination.pageNum = page.pageNum
    pagination.pageSize = page.pageSize
    await renderResultFootprints(page.records)
  } finally {
    loading.value = false
  }
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
