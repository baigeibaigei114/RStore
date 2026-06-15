<template>
  <section>
    <div class="page-title">
      <span>影像资产</span>
      <h2>影像列表</h2>
      <p>按名称、传感器、云量和采集时间筛选影像，可进入详情查看元数据和访问控制状态。</p>
    </div>

    <el-card class="ai-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>AI 智能检索</span>
          <el-tag size="small" effect="plain">只解析，不自动执行</el-tag>
        </div>
      </template>

      <el-input
        v-model="aiInput"
        type="textarea"
        :rows="3"
        maxlength="1000"
        show-word-limit
        placeholder="例如：找上海黄浦区2024年云量小于20%的Sentinel-2影像，并计算NDVI"
      />

      <div class="ai-actions">
        <el-button :loading="aiParseLoading" :icon="Search" @click="handleAiParse">解析条件</el-button>
        <el-button type="primary" :loading="aiPlanLoading" @click="handleCreateAiPlan">生成计划</el-button>
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
        行政区“{{ aiParsed.regionName }}”已作为提示展示。当前影像列表仍走普通检索接口；如需严格行政区空间检索，请进入空间查询页选择行政区后检索。
      </div>

      <div v-if="aiParsed?.taskTypes?.length" class="ai-task-tags">
        <span>AI 建议后续任务</span>
        <el-tag v-for="type in aiParsed.taskTypes" :key="type" effect="plain">{{ taskTypeText(type) }}</el-tag>
      </div>
    </el-card>

    <el-card class="filter-card" shadow="never">
      <el-form :model="queryForm" label-position="top">
        <el-row :gutter="16">
          <el-col :xs="24" :sm="12" :lg="6">
            <el-form-item label="关键字">
              <el-input v-model="queryForm.keyword" clearable placeholder="影像名称或编码" />
            </el-form-item>
          </el-col>
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
          <el-col :xs="24" :sm="12" :lg="6">
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

        <div class="filter-actions">
          <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
          <el-button type="primary" :icon="Search" @click="submitSearch">查询</el-button>
          <el-button type="success" :icon="UploadFilled" @click="router.push('/images/upload')">
            上传影像
          </el-button>
        </div>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="imageList" row-key="id" empty-text="暂无影像数据">
        <el-table-column prop="imageName" label="影像名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="sensorType" label="传感器" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.sensorType || '未填写' }}
          </template>
        </el-table-column>
        <el-table-column label="尺寸" width="130">
          <template #default="{ row }">
            {{ formatSize(row.width, row.height) }}
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
        <el-table-column label="可见性" width="110">
          <template #default="{ row }">
            <el-tag :type="row.visibility === 'PUBLIC' ? 'success' : 'info'" effect="plain">
              {{ row.visibility === 'PUBLIC' ? '公开' : '私有' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="缩略图" width="120">
          <template #default="{ row }">
            <el-tag :type="thumbnailStatusType(row.thumbnailStatus)" effect="plain">
              {{ thumbnailStatusText(row.thumbnailStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="采集时间" min-width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.acquisitionTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              link
              type="danger"
              :disabled="!canDeleteImage(row)"
              :loading="deletingId === row.id"
              @click="confirmDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="pagination.pageNum"
          v-model:page-size="pagination.pageSize"
          :page-sizes="[10, 20, 50]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="fetchImages"
          @current-change="fetchImages"
        />
      </div>
    </el-card>

    <AiPlanDrawer
      v-model:visible="aiPlanDrawerVisible"
      :plan="aiPlan"
      :confirm-loading="aiPlanConfirmLoading"
      :cancel-loading="aiPlanCancelLoading"
      @confirm="handleConfirmAiPlan"
      @cancel="handleCancelAiPlan"
    />
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search, UploadFilled } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import {
  cancelAiPlanApi,
  confirmAiPlanApi,
  createAiPlanApi,
  parseAiQueryApi,
} from '@/api/ai'
import { deleteImageApi, searchImagesApi } from '@/api/image'
import AiPlanDrawer from '@/components/ai/AiPlanDrawer.vue'
import type { AiPlan, AiQueryParseResult } from '@/types/ai'
import type {
  ImageListItem,
  ImageSearchParams,
  ImageStatus,
  ThumbnailStatus,
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
const deletingId = ref<number>()
const aiInput = ref('')
const aiParseLoading = ref(false)
const aiPlanLoading = ref(false)
const aiPlanConfirmLoading = ref(false)
const aiPlanCancelLoading = ref(false)
const aiParsed = ref<AiQueryParseResult | null>(null)
const aiPlan = ref<AiPlan | null>(null)
const aiPlanDrawerVisible = ref(false)
const imageList = ref<ImageListItem[]>([])

const queryForm = reactive<QueryForm>({
  keyword: '',
  sensor: '',
  maxCloudPercent: undefined,
  timeRange: [],
})

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

async function handleCreateAiPlan() {
  if (!aiInput.value.trim()) {
    ElMessage.warning('请先输入自然语言需求')
    return
  }

  aiPlanLoading.value = true
  try {
    aiPlan.value = await createAiPlanApi(aiInput.value.trim())
    aiPlanDrawerVisible.value = true
  } finally {
    aiPlanLoading.value = false
  }
}

async function handleConfirmAiPlan() {
  if (!aiPlan.value) {
    return
  }

  aiPlanConfirmLoading.value = true
  try {
    aiPlan.value = await confirmAiPlanApi(aiPlan.value.id)
    ElMessage.success('计划已确认。请继续手动检索影像或提交任务。')
  } finally {
    aiPlanConfirmLoading.value = false
  }
}

async function handleCancelAiPlan() {
  if (!aiPlan.value) {
    return
  }

  aiPlanCancelLoading.value = true
  try {
    aiPlan.value = await cancelAiPlanApi(aiPlan.value.id)
    ElMessage.success('计划已取消')
  } finally {
    aiPlanCancelLoading.value = false
  }
}

function applyAiParsedResult(result: AiQueryParseResult) {
  queryForm.sensor = result.sensor || ''
  queryForm.maxCloudPercent = result.maxCloudPercent
  queryForm.timeRange = result.startTime && result.endTime ? [result.startTime, result.endTime] : []
  pagination.pageNum = 1
}

function openDetail(row: ImageListItem) {
  router.push(`/images/${row.id}`)
}

function canDeleteImage(row: ImageListItem) {
  return row.status === 'READY' || row.status === 'FAILED'
}

async function confirmDelete(row: ImageListItem) {
  if (!canDeleteImage(row)) {
    ElMessage.warning('当前影像状态不允许删除')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认删除影像“${row.imageName}”？删除后将从列表和空间检索中隐藏，历史任务记录仍会保留。`,
      '删除影像',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }

  deletingId.value = row.id
  try {
    await deleteImageApi(row.id)
    ElMessage.success('影像已删除')
    fetchImages()
  } finally {
    deletingId.value = undefined
  }
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

function thumbnailStatusText(status?: ThumbnailStatus) {
  if (!status) {
    return '无'
  }
  const map: Record<ThumbnailStatus, string> = {
    PENDING: '等待中',
    RUNNING: '生成中',
    SUCCESS: '已生成',
    FAILED: '失败',
    SKIPPED: '已跳过',
  }
  return map[status] || status
}

function thumbnailStatusType(status?: ThumbnailStatus) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'PENDING' || status === 'RUNNING') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'info'
}
</script>
