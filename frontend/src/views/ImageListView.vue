<template>
  <section class="image-catalog">
    <div class="page-title">
      <span>我的影像</span>
      <h2>选择要查看的影像</h2>
      <p>按名称查找影像，打开详情后可开始区域分析。</p>
    </div>

    <el-card class="filter-card" shadow="never">
      <el-form :model="queryForm" label-position="top">
        <div class="basic-query">
          <el-form-item label="影像名称">
            <el-input v-model="queryForm.keyword" clearable placeholder="输入影像名称" @keyup.enter="submitSearch" />
          </el-form-item>
          <div class="query-actions">
            <el-button type="primary" :icon="Search" :loading="loading" @click="submitSearch">查询</el-button>
            <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
            <el-button :aria-expanded="moreFilters" aria-controls="image-advanced-filters" @click="moreFilters = !moreFilters">
              {{ moreFilters ? '收起筛选' : '更多筛选' }}{{ selectedFilterCount ? `（已设置 ${selectedFilterCount} 项）` : '' }}
            </el-button>
            <el-button :icon="UploadFilled" @click="router.push('/images/upload')">上传影像</el-button>
          </div>
        </div>
        <el-row v-show="moreFilters" id="image-advanced-filters" :gutter="16">
          <el-col :xs="24" :sm="12" :lg="6">
            <el-form-item label="传感器">
              <el-input v-model="queryForm.sensor" clearable placeholder="例如 Sentinel-2" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="12" :lg="6">
            <el-form-item label="最大云量">
              <el-input-number
                v-model="queryForm.maxCloudPercent"
                :min="0"
                :max="100"
                :precision="2"
                :step="5"
                controls-position="right"
                placeholder="0-100"
                class="full-width"
              />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="24" :lg="12">
            <el-form-item label="采集时间">
              <el-date-picker
                v-model="queryForm.timeRange"
                type="datetimerange"
                start-placeholder="开始时间"
                end-placeholder="结束时间"
                value-format="YYYY-MM-DDTHH:mm:ssZ"
                class="full-width"
              />
            </el-form-item>
          </el-col>
        </el-row>

      </el-form>
    </el-card>

    <el-collapse class="optional-search">
      <el-collapse-item title="用自然语言筛选（可选）" name="ai">
      <p class="ai-explanation">将一句话整理成筛选条件，由你核对后再查询。</p>

      <el-input
        v-model="aiInput"
        type="textarea"
        :rows="3"
        maxlength="1000"
        show-word-limit
        placeholder="例如：查找 2024 年云量低于 20% 的 Sentinel-2 影像"
      />

      <div class="ai-actions">
        <el-button :loading="aiParseLoading" :icon="Search" @click="handleAiParse">解析条件</el-button>
        <el-button type="primary" @click="router.push('/agent')">打开NDVI助手</el-button>
      </div>

      <el-alert
        v-if="aiParsed"
        type="success"
        show-icon
        :closable="false"
        title="AI 已解析条件，请确认后点击下方查询按钮"
        class="ai-result-alert"
      />

      <div v-if="aiParsed" class="ai-result-grid">
        <div>
          <span>区域提示</span>
          <strong>{{ aiParsed.regionName || '未识别' }}</strong>
        </div>
        <div>
          <span>时间范围</span>
          <strong>{{ formatAiRange(aiParsed.startTime, aiParsed.endTime) }}</strong>
        </div>
        <div>
          <span>传感器</span>
          <strong>{{ aiParsed.sensor || '未识别' }}</strong>
        </div>
        <div>
          <span>最大云量</span>
          <strong>{{ aiParsed.maxCloudPercent ?? '未识别' }}</strong>
        </div>
      </div>

      <div v-if="aiParsed?.regionName" class="ai-region-note">
        已识别行政区“{{ aiParsed.regionName }}”。若要按行政区范围查找，请在空间查询中选择该区域。
      </div>

      <div v-if="aiParsed?.taskTypes?.length" class="ai-task-tags">
        <span>AI 建议后续任务</span>
        <el-tag v-for="type in aiParsed.taskTypes" :key="type" effect="plain">{{ taskTypeText(type) }}</el-tag>
      </div>
      </el-collapse-item>
    </el-collapse>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="imageList" row-key="id" empty-text="暂无影像数据">
        <el-table-column label="影像" min-width="260">
          <template #default="{ row }">
            <div class="image-identity"><strong>{{ row.imageName || '未命名影像' }}</strong>
              <small>{{ row.sensorType || '未记录来源' }} · {{ row.visibility === 'PUBLIC' ? '公开' : '私有' }}</small></div>
          </template>
        </el-table-column>
        <el-table-column label="云量" width="100">
          <template #default="{ row }">
            {{ formatCloud(row.cloudPercent) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="imageStatusType(row.status)" effect="plain">
              {{ imageStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="采集时间" min-width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.acquisitionTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看影像</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="pagination.pageNum"
          v-model:page-size="pagination.pageSize"
          :page-sizes="[10, 20, 50]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next"
          @size-change="fetchImages"
          @current-change="fetchImages"
        />
      </div>
    </el-card>

  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search, UploadFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { parseAiQueryApi } from '@/api/ai'
import { searchImagesApi } from '@/api/image'
import type { AiQueryParseResult } from '@/types/ai'
import type {
  ImageListItem,
  ImageSearchParams,
  ImageStatus,
} from '@/types/image'
import type { TaskType } from '@/types/task'

interface QueryForm {
  keyword: string
  sensor: string
  maxCloudPercent?: number
  timeRange: string[]
}

const router = useRouter()
const loading = ref(false)
const moreFilters = ref(false)
const aiInput = ref('')
const aiParseLoading = ref(false)
const aiParsed = ref<AiQueryParseResult | null>(null)
const imageList = ref<ImageListItem[]>([])

const queryForm = reactive<QueryForm>({
  keyword: '',
  sensor: '',
  maxCloudPercent: undefined,
  timeRange: [],
})
const selectedFilterCount = computed(() => Number(Boolean(queryForm.sensor.trim()))
  + Number(queryForm.maxCloudPercent !== undefined && queryForm.maxCloudPercent !== null)
  + Number(Boolean(queryForm.timeRange?.length)))

const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0,
})

onMounted(() => {
  fetchImages()
})

async function fetchImages() {
  loading.value = true
  try {
    const params = buildSearchParams()
    const page = await searchImagesApi(params)
    imageList.value = page.records
    pagination.total = page.total
    pagination.pageNum = page.pageNum
    pagination.pageSize = page.pageSize
  } finally {
    loading.value = false
  }
}

function buildSearchParams(): ImageSearchParams {
  return {
    keyword: queryForm.keyword || undefined,
    sensor: queryForm.sensor || undefined,
    maxCloudPercent: queryForm.maxCloudPercent,
    startTime: queryForm.timeRange?.[0],
    endTime: queryForm.timeRange?.[1],
    pageNum: pagination.pageNum,
    pageSize: pagination.pageSize,
  }
}

function submitSearch() {
  pagination.pageNum = 1
  fetchImages()
}

function resetSearch() {
  queryForm.keyword = ''
  queryForm.sensor = ''
  queryForm.maxCloudPercent = undefined
  queryForm.timeRange = []
  pagination.pageNum = 1
  fetchImages()
}

async function handleAiParse() {
  if (!aiInput.value.trim()) {
    ElMessage.warning('请先输入自然语言检索需求')
    return
  }

  aiParseLoading.value = true
  try {
    const result = await parseAiQueryApi(aiInput.value.trim())
    aiParsed.value = result
    applyAiParsedResult(result)
    ElMessage.success('AI 已解析条件，请确认后手动查询')
  } finally {
    aiParseLoading.value = false
  }
}

function applyAiParsedResult(result: AiQueryParseResult) {
  queryForm.sensor = result.sensor || ''
  queryForm.maxCloudPercent = result.maxCloudPercent
  queryForm.timeRange = result.startTime && result.endTime ? [result.startTime, result.endTime] : []
  moreFilters.value = true
  pagination.pageNum = 1
}

function openDetail(row: ImageListItem) {
  router.push(`/images/${row.id}`)
}

function formatCloud(value?: number) {
  if (value === undefined || value === null) {
    return '未知'
  }
  return `${Number(value).toFixed(2)}%`
}

function formatDateTime(value?: string) {
  if (!value) {
    return '未填写'
  }
  return new Date(value).toLocaleString()
}

function formatAiRange(startTime?: string, endTime?: string) {
  if (!startTime && !endTime) {
    return '未识别'
  }
  return `${startTime || '未识别'} 至 ${endTime || '未识别'}`
}

function taskTypeText(type: TaskType) {
  const map: Record<TaskType, string> = {
    NDVI: '植被指数',
    NDWI: '水体指数',
    CHANGE_DETECTION: '变化检测',
  }
  return map[type] || type
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

function imageStatusType(status: ImageStatus) {
  if (status === 'READY') return 'success'
  if (status === 'PROCESSING' || status === 'UPLOADING' || status === 'PARSING') return 'warning'
  if (status === 'FAILED' || status === 'DELETED') return 'danger'
  return 'info'
}

</script>

<style scoped>
.image-catalog { max-width: 1160px; --el-color-primary: #176b52; }
.basic-query { display: grid; grid-template-columns: minmax(200px, 1fr) auto; align-items: end; gap: 16px; }
.basic-query .el-form-item { margin: 0; }
.query-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.query-actions .el-button { margin: 0; }
#image-advanced-filters { margin-top: 20px; }
.optional-search { margin: 0 0 16px; padding: 0 20px; border: 1px solid #e4e7ed; border-radius: 8px; background: #fff; }
.optional-search :deep(.el-collapse-item__header) { font-weight: 500; }
.optional-search :deep(.el-collapse-item__wrap) { border-bottom: 0; }
.ai-explanation { margin: 0 0 12px; color: #667085; }
.image-identity strong, .image-identity small { display: block; overflow-wrap: anywhere; }
.image-identity strong { font-weight: 600; }
.image-identity small { color: #667085; font-size: 12px; }
@media (max-width: 1000px) { .basic-query { grid-template-columns: 1fr; } }
</style>
