<template>
  <section>
    <div class="page-title">
      <span>任务详情</span>
      <h2>{{ task?.taskName || task?.taskCode || `任务 ID：${taskId}` }}</h2>
      <p>查看任务状态、进度、输入输出对象、错误信息和 Worker 回调日志。</p>
    </div>

    <div class="detail-actions">
      <el-button @click="router.push('/tasks')">返回列表</el-button>
      <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
      <el-button
        type="primary"
        :loading="resultDownloadLoading"
        :disabled="!task?.outputObjectKey"
        @click="downloadTaskResult"
      >
        下载结果影像
      </el-button>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="16">
        <el-card class="detail-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>任务概要</span>
              <el-tag v-if="task" :type="taskStatusType(task.status)" effect="plain">
                {{ taskStatusText(task.status) }}
              </el-tag>
            </div>
          </template>

          <el-skeleton v-if="loading && !task" :rows="8" animated />

          <template v-else-if="task">
            <el-progress
              :percentage="task.progress ?? 0"
              :status="progressStatus(task.status)"
              class="task-progress"
            />

            <el-descriptions :column="2" border>
              <el-descriptions-item label="任务编码">{{ task.taskCode }}</el-descriptions-item>
              <el-descriptions-item label="任务类型">{{ taskTypeText(task.taskType) }}</el-descriptions-item>
              <el-descriptions-item label="影像名称">{{ task.imageName }}</el-descriptions-item>
              <el-descriptions-item label="影像 ID">{{ task.imageId }}</el-descriptions-item>
              <el-descriptions-item label="重试次数">
                {{ task.retryCount ?? 0 }} / {{ task.maxRetryCount ?? 0 }}
              </el-descriptions-item>
              <el-descriptions-item label="归属用户">{{ task.ownerId }}</el-descriptions-item>
              <el-descriptions-item label="提交时间">{{ formatDateTime(task.submittedAt) }}</el-descriptions-item>
              <el-descriptions-item label="开始时间">{{ formatDateTime(task.startedAt) }}</el-descriptions-item>
              <el-descriptions-item label="完成时间">{{ formatDateTime(task.finishedAt) }}</el-descriptions-item>
              <el-descriptions-item label="更新时间">{{ formatDateTime(task.updatedAt) }}</el-descriptions-item>
              <el-descriptions-item label="错误信息" :span="2">
                <span :class="{ 'error-text-inline': Boolean(task.errorMessage) }">
                  {{ task.errorMessage || '无' }}
                </span>
              </el-descriptions-item>
            </el-descriptions>
          </template>

          <el-empty v-else description="暂无任务详情" />
        </el-card>

        <el-card class="detail-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>AI 分析报告</span>
              <el-tag :type="task?.status === 'SUCCESS' ? 'success' : 'info'" effect="plain">
                {{ task?.status === 'SUCCESS' ? '可生成' : '等待任务成功' }}
              </el-tag>
            </div>
          </template>

          <div class="ai-report-actions">
            <el-button
              type="primary"
              :loading="aiReportLoading"
              :disabled="task?.status !== 'SUCCESS'"
              @click="handleGenerateAiReport"
            >
              生成 AI 报告
            </el-button>
            <span>AI 只生成报告，不会修改任务或结果文件。</span>
          </div>

          <template v-if="aiReport">
            <el-descriptions :column="2" border class="ai-report-summary">
              <el-descriptions-item label="报告类型">{{ aiReport.reportType || '未知' }}</el-descriptions-item>
              <el-descriptions-item label="风险等级">
                <el-tag :type="riskLevelType(aiRiskLevel)" effect="plain">{{ riskLevelText(aiRiskLevel) }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="生成时间" :span="2">
                {{ formatDateTime(aiReport.createdAt) }}
              </el-descriptions-item>
              <el-descriptions-item label="摘要" :span="2">
                {{ aiReport.summary || '暂无摘要' }}
              </el-descriptions-item>
            </el-descriptions>

            <div class="ai-report-block">
              <h4>关键发现</h4>
              <ul v-if="aiKeyFindings.length">
                <li v-for="finding in aiKeyFindings" :key="finding">{{ finding }}</li>
              </ul>
              <p v-else class="muted-text">暂无关键发现</p>
            </div>

            <div class="ai-report-block">
              <h4>建议</h4>
              <ul v-if="aiSuggestions.length">
                <li v-for="suggestion in aiSuggestions" :key="suggestion">{{ suggestion }}</li>
              </ul>
              <p v-else class="muted-text">暂无建议</p>
            </div>
          </template>

          <el-empty v-else description="暂无 AI 报告，成功任务可手动生成" />
        </el-card>

        <el-card class="detail-card" shadow="never">
          <template #header>任务日志</template>

          <el-empty v-if="!logLoading && logs.length === 0" description="暂无日志" />

          <el-timeline v-else v-loading="logLoading" class="task-log-timeline">
            <el-timeline-item
              v-for="log in logs"
              :key="log.id"
              :timestamp="formatDateTime(log.createdAt)"
              :type="logLevelType(log.logLevel)"
              placement="top"
            >
              <div class="task-log-item">
                <div class="task-log-title">
                  <strong>{{ log.logLevel }}</strong>
                  <span>{{ log.message }}</span>
                </div>
                <pre v-if="log.detail" class="code-block task-log-detail">{{ log.detail }}</pre>
              </div>
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="8">
        <el-card class="detail-card" shadow="never">
          <template #header>对象路径</template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="输入存储桶">{{ task?.inputBucket || '未知' }}</el-descriptions-item>
            <el-descriptions-item label="输入对象">{{ task?.inputObjectKey || '未知' }}</el-descriptions-item>
            <el-descriptions-item label="输出存储桶">{{ task?.outputBucket || '暂无' }}</el-descriptions-item>
            <el-descriptions-item label="输出对象">{{ task?.outputObjectKey || '暂无' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card class="detail-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>结果发布</span>
              <el-tag v-if="resultFile?.status" :type="resultStatusType(resultFile.status)" effect="plain">
                {{ resultStatusText(resultFile.status) }}
              </el-tag>
            </div>
          </template>

          <el-skeleton v-if="resultLoading" :rows="5" animated />
          <template v-else-if="resultFile">
            <el-descriptions :column="1" border>
              <el-descriptions-item label="结果文件">{{ resultFile.fileName || '暂无' }}</el-descriptions-item>
              <el-descriptions-item label="可见性">
                {{ resultFile.visibility === 'PUBLIC' ? '公开' : '私有' }}
              </el-descriptions-item>
              <el-descriptions-item label="图层名称">
                {{ qualifiedResultLayerName || '暂无' }}
              </el-descriptions-item>
              <el-descriptions-item label="发布时间">
                {{ formatDateTime(resultFile.publishedAt) }}
              </el-descriptions-item>
              <el-descriptions-item label="发布错误">
                <span :class="{ 'error-text-inline': Boolean(resultFile.publishErrorMessage) }">
                  {{ resultFile.publishErrorMessage || '无' }}
                </span>
              </el-descriptions-item>
            </el-descriptions>

            <div class="map-panel-actions">
              <el-button
                :disabled="!resultFile.imageId || !task?.taskType"
                @click="openResultLayerInMap"
              >
                地图查看
              </el-button>
            </div>
          </template>
          <el-empty v-else description="暂无结果发布信息" />
        </el-card>

        <el-card class="detail-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>轮询状态</span>
              <el-tag :type="polling ? 'success' : 'info'" effect="plain">
                {{ polling ? '自动刷新中' : '已停止' }}
              </el-tag>
            </div>
          </template>
          <p class="muted-text">
            任务处于等待、运行或重试状态时，页面每 3 秒自动刷新任务详情和日志。
          </p>
        </el-card>

        <el-card class="detail-card" shadow="never">
          <template #header>任务参数</template>
          <pre class="code-block">{{ formattedParams }}</pre>
        </el-card>
      </el-col>
    </el-row>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { generateTaskReportApi } from '@/api/ai'
import {
  getTaskDetailApi,
  getTaskResultDownloadUrlApi,
  getTaskResultFileApi,
  listTaskLogsApi,
} from '@/api/task'
import type { AiRiskLevel, AiTaskReport } from '@/types/ai'
import type { TaskDetail, TaskLog, TaskResultFile, TaskStatus, TaskType } from '@/types/task'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const logLoading = ref(false)
const resultDownloadLoading = ref(false)
const resultLoading = ref(false)
const aiReportLoading = ref(false)
const polling = ref(false)
const task = ref<TaskDetail | null>(null)
const resultFile = ref<TaskResultFile | null>(null)
const aiReport = ref<AiTaskReport | null>(null)
const logs = ref<TaskLog[]>([])
const pollingTimer = ref<number>()

const taskId = computed(() => String(route.params.id))

const formattedParams = computed(() => {
  if (!task.value?.params) {
    return '暂无任务参数'
  }

  try {
    return JSON.stringify(JSON.parse(task.value.params), null, 2)
  } catch {
    return task.value.params
  }
})

const qualifiedResultLayerName = computed(() => {
  if (!resultFile.value?.workspace || !resultFile.value?.layerName) {
    return ''
  }
  return `${resultFile.value.workspace}:${resultFile.value.layerName}`
})

const aiRiskLevel = computed(() => String(aiReport.value?.reportJson?.riskLevel || 'UNKNOWN'))

const aiKeyFindings = computed(() => normalizeTextList(aiReport.value?.reportJson?.keyFindings))

const aiSuggestions = computed(() => normalizeTextList(aiReport.value?.reportJson?.suggestions))

onMounted(() => {
  refreshAll()
})

onBeforeUnmount(() => {
  stopPolling()
})

watch(
  () => task.value?.status,
  () => {
    syncPolling()
  },
)

async function refreshAll() {
  await fetchTask()

  const jobs: Promise<void>[] = [fetchLogs()]
  if (task.value?.status === 'SUCCESS' || task.value?.outputObjectKey) {
    jobs.push(fetchResultFile())
  } else {
    resultFile.value = null
  }

  await Promise.all(jobs)
}

async function fetchTask() {
  loading.value = true
  try {
    task.value = await getTaskDetailApi(taskId.value)
  } finally {
    loading.value = false
  }
}

async function fetchLogs() {
  logLoading.value = true
  try {
    logs.value = await listTaskLogsApi(taskId.value)
  } finally {
    logLoading.value = false
  }
}

async function fetchResultFile() {
  resultLoading.value = true
  try {
    resultFile.value = await getTaskResultFileApi(taskId.value, true)
  } catch {
    resultFile.value = null
  } finally {
    resultLoading.value = false
  }
}

async function downloadTaskResult() {
  if (!task.value?.outputObjectKey) {
    return
  }

  resultDownloadLoading.value = true
  try {
    const presigned = await getTaskResultDownloadUrlApi(taskId.value)
    window.open(presigned.url, '_blank', 'noopener,noreferrer')
  } finally {
    resultDownloadLoading.value = false
  }
}

async function handleGenerateAiReport() {
  if (task.value?.status !== 'SUCCESS') {
    ElMessage.warning('任务成功后才能生成 AI 报告')
    return
  }

  aiReportLoading.value = true
  try {
    aiReport.value = await generateTaskReportApi(taskId.value, true)
    ElMessage.success('AI 报告已生成')
  } catch (error) {
    const message = error instanceof Error ? error.message : 'AI 服务暂不可用，请稍后重试'
    if (message.includes('统计元数据')) {
      ElMessage.warning('该任务缺少统计元数据，暂无法生成报告')
    } else {
      ElMessage.error(message || 'AI 服务暂不可用，请稍后重试')
    }
  } finally {
    aiReportLoading.value = false
  }
}

function syncPolling() {
  if (task.value && isActiveStatus(task.value.status)) {
    startPolling()
    return
  }
  stopPolling()
}

function openResultLayerInMap() {
  if (!resultFile.value?.imageId || !task.value?.taskType) {
    return
  }

  router.push({
    path: '/map',
    query: {
      imageId: String(resultFile.value.imageId),
      taskType: task.value.taskType,
    },
  })
}

function startPolling() {
  polling.value = true

  if (pollingTimer.value) {
    return
  }

  pollingTimer.value = window.setInterval(() => {
    refreshAll()
  }, 3000)
}

function stopPolling() {
  polling.value = false

  if (pollingTimer.value) {
    window.clearInterval(pollingTimer.value)
    pollingTimer.value = undefined
  }
}

function isActiveStatus(status: TaskStatus) {
  return status === 'PENDING' || status === 'RUNNING' || status === 'RETRYING'
}

function taskTypeText(type: TaskType) {
  const map: Record<TaskType, string> = {
    NDVI: '植被指数',
    NDWI: '水体指数',
    CHANGE_DETECTION: '变化检测',
  }
  return map[type] || type
}

function taskStatusText(status: TaskStatus) {
  const map: Record<TaskStatus, string> = {
    PENDING: '等待中',
    RUNNING: '运行中',
    SUCCESS: '成功',
    FAILED: '失败',
    RETRYING: '重试中',
    CANCELED: '已取消',
  }
  return map[status] || status
}

function taskStatusType(status: TaskStatus) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'CANCELED') return 'danger'
  if (status === 'RUNNING' || status === 'PENDING' || status === 'RETRYING') return 'warning'
  return 'info'
}

function resultStatusText(status: string) {
  const map: Record<string, string> = {
    PENDING: '待发布',
    PUBLISHING: '发布中',
    PUBLISHED: '已发布',
    FAILED: '发布失败',
  }
  return map[status] || status
}

function resultStatusType(status: string) {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PUBLISHING' || status === 'PENDING') return 'warning'
  return 'info'
}

function normalizeTextList(value?: unknown) {
  if (!Array.isArray(value)) {
    return []
  }

  return value.map((item) => String(item)).filter(Boolean)
}

function riskLevelText(level: string) {
  const map: Record<string, string> = {
    LOW: '低',
    MEDIUM: '中',
    HIGH: '高',
    UNKNOWN: '未知',
  }
  return map[level] || level
}

function riskLevelType(level: string) {
  const normalized = level as AiRiskLevel
  if (normalized === 'LOW') return 'success'
  if (normalized === 'MEDIUM') return 'warning'
  if (normalized === 'HIGH') return 'danger'
  return 'info'
}

function progressStatus(status: TaskStatus) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'CANCELED') return 'exception'
  return undefined
}

function logLevelType(level: string) {
  const normalized = level?.toUpperCase()
  if (normalized === 'ERROR') return 'danger'
  if (normalized === 'WARN' || normalized === 'WARNING') return 'warning'
  if (normalized === 'INFO') return 'primary'
  return 'info'
}

function formatDateTime(value?: string) {
  if (!value) {
    return '未填写'
  }
  return new Date(value).toLocaleString()
}
</script>
