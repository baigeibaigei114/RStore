<template>
  <section class="analysis-result">
    <div class="page-title">
      <span>任务详情</span>
      <h2>{{ task?.imageName || '读取分析结果' }}</h2>
      <p>{{ regionSnapshot ? `${regionSnapshot.name} · 版本 ${regionSnapshot.version}` : '分析范围与结果以提交时快照为准' }}</p>
    </div>

    <el-alert v-if="connectionError" :title="connectionError" type="warning" show-icon :closable="false" class="result-feedback" />
    <el-button v-if="expired" @click="relogin">重新登录并返回原任务</el-button>
    <el-card shadow="never" class="result-summary">
      <div class="result-state"><strong>{{ taskStatusText(task?.status) }}</strong><span>最近成功读取：{{ lastRead || '尚未读取' }}</span></div>
      <template v-if="ndviStatistics">
        <div class="statistics-grid"><div><span>{{ regionSnapshot ? '区域 NDVI 均值' : 'NDVI 均值' }}</span><strong>{{ ndviStatistics.mean.toFixed(4) }}</strong><small>{{ regionSnapshot ? '仅统计区域内的有效像素' : '统计整幅裁剪矩形内有效像素' }}</small></div>
          <div><span>有效像素比例</span><strong>{{ (ndviStatistics.validPixelRatio * 100).toFixed(2) }}<small>%</small></strong><small>{{ ndviStatistics.validPixelCount.toLocaleString() }} / {{ ndviStatistics.totalPixelCount.toLocaleString() }} 个区域内像素</small></div></div>
        <p class="result-note">有效像素比例不是植被覆盖率；单期 NDVI 不能直接判断作物健康或预测产量。</p>
      </template>
      <p v-else-if="task?.status === 'SUCCESS'">计算已完成，暂未取得可用统计。已保留任务，可刷新结果。</p>
      <p v-else-if="isActiveStatus(task?.status)">任务已受理，你可以离开此页，稍后从最近分析、任务列表或原链接继续查看。</p>
      <div v-if="task?.status === 'FAILED' || task?.status === 'CANCELED'" class="failure"><p>{{ task.status === 'CANCELED' ? '本次任务已取消。可带回原选项，核对后重新发起分析。' : failureMessage(task.errorMessage) }}</p><el-button @click="returnOptions">带回原选项重新核对</el-button></div>
      <p v-if="task?.status === 'SUCCESS'" class="result-note">地图：{{ resultFile?.status === 'PUBLISHED' ? '已就绪' : ['FAILED', 'PUBLISH_FAILED'].includes(resultFile?.status || '') ? '发布失败，计算结果仍可查看' : '尚未就绪，统计可独立查看' }}</p>
    </el-card>

    <div class="detail-actions">
      <el-button @click="router.push('/tasks')">返回列表</el-button>
      <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
      <el-button v-if="resultFile?.status === 'PUBLISHED' && qualifiedResultLayerName" @click="openResultLayerInMap">查看地图</el-button>
      <el-button
        type="primary"
        :loading="resultDownloadLoading"
        :disabled="task?.status !== 'SUCCESS' || !task?.outputObjectKey"
        @click="downloadTaskResult"
      >
        下载结果影像
      </el-button>
    </div>

    <el-collapse v-model="technicalOpen"><el-collapse-item title="技术详情与更多结果" name="details">
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

        <InputProvenance v-if="task?.taskType === 'NDVI'" :raw="task.params" snapshot />
        <el-card v-if="task?.taskType === 'NDVI'" class="detail-card" shadow="never">
          <template #header>NDVI 计算统计</template>
          <template v-if="ndviStatistics">
            <p>范围：{{ regionSnapshot ? '监测区域内像素（像素中心归属）' : '输入影像的裁剪矩形' }}；无效像素不参与最小值、最大值和均值统计。</p>
            <el-alert v-if="regionSnapshot" type="info" :closable="false"
              :title="`${regionSnapshot.name} · v${regionSnapshot.version} · 整区覆盖；有效比例不是植被覆盖率`" />
            <el-alert
              v-if="ndviStatistics.min < -1 || ndviStatistics.max > 1"
              title="数值超出常见 NDVI 范围，请核对输入反射率、偏移和质量掩膜；不要直接据此判断植被情况。"
              type="warning"
              :closable="false"
              show-icon
            />
            <el-descriptions :column="2" border>
              <el-descriptions-item label="总像素">{{ ndviStatistics.totalPixelCount }}</el-descriptions-item>
              <el-descriptions-item label="有效像素">{{ ndviStatistics.validPixelCount }}</el-descriptions-item>
              <el-descriptions-item label="无效像素">{{ ndviStatistics.invalidPixelCount }}</el-descriptions-item>
              <el-descriptions-item label="有效比例">{{ (ndviStatistics.validPixelRatio * 100).toFixed(2) }}%</el-descriptions-item>
              <el-descriptions-item label="最小值">{{ ndviStatistics.min.toFixed(6) }}</el-descriptions-item>
              <el-descriptions-item label="最大值">{{ ndviStatistics.max.toFixed(6) }}</el-descriptions-item>
              <el-descriptions-item label="平均值">{{ ndviStatistics.mean.toFixed(6) }}</el-descriptions-item>
            </el-descriptions>
          </template>
          <el-empty v-else description="尚无可用统计；旧任务可能未生成统计，不能将缺失值视为 0" />
          <el-collapse v-if="regionSnapshot">
            <el-collapse-item title="提交时的区域快照（不随区域编辑改变）">
              <pre class="code-block">{{ JSON.stringify(regionSnapshot, null, 2) }}</pre>
            </el-collapse-item>
          </el-collapse>
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
                :disabled="resultFile.status !== 'PUBLISHED' || !resultFile.imageId || !task?.taskType"
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
            任务处理或结果发布期间，页面每 3 秒自动刷新。计算成功不代表地图已经发布成功。
          </p>
        </el-card>

        <el-card class="detail-card" shadow="never">
          <template #header>任务参数</template>
          <pre class="code-block">{{ formattedParams }}</pre>
        </el-card>
      </el-col>
    </el-row>
    </el-collapse-item></el-collapse>
  </section>
</template>

<script setup lang="ts">
import InputProvenance from '@/components/InputProvenance.vue'
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
import { useAuthStore } from '@/stores/auth'
import { useTaskRefresh } from '@/composables/useTaskRefresh'
import { isUnauthorized } from '@/utils/regionAnalysis'
import { failureMessage, isActiveStatus, readStatistics, regionSnapshot as getRegionSnapshot, taskStatusText } from '@/utils/taskPresentation'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const logLoading = ref(false)
const resultDownloadLoading = ref(false)
const resultLoading = ref(false)
const aiReportLoading = ref(false)
const auth = useAuthStore()
const connectionError = ref(''), expired = ref(false), lastRead = ref('')
const technicalOpen = ref<string[]>([])
const task = ref<TaskDetail | null>(null)
const resultFile = ref<TaskResultFile | null>(null)
const aiReport = ref<AiTaskReport | null>(null)
const logs = ref<TaskLog[]>([])

const taskId = computed(() => String(route.params.id))

const regionSnapshot = computed(() => getRegionSnapshot(task.value))
const ndviStatistics = computed(() => readStatistics(task.value, resultFile.value))

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

let alive = true, generation = 0, terminalReads = 0
const { polling, refresh, stop } = useTaskRefresh(readResult)
function refreshAll() { terminalReads = 0; refresh() }
onMounted(async () => {
  try { const user = await auth.loadCurrentUser(); if (alive && user) refreshAll() }
  catch (error) { if (alive) showConnectionError(error) }
})
onBeforeUnmount(() => { alive = false; generation++; stop() })
watch(() => [taskId.value, auth.userInfo?.userId], () => {
  generation++; terminalReads = 0; task.value = null; resultFile.value = null
  logs.value = []; aiReport.value = null; connectionError.value = ''; lastRead.value = ''
  if (alive && auth.userInfo?.userId) refresh(); else stop()
})
watch(technicalOpen, async value => {
  const id = taskId.value, epoch = generation
  if (!value.includes('details') || !task.value) return
  logLoading.value = true
  try { const value = await listTaskLogsApi(id); if (alive && epoch === generation) logs.value = value }
  catch { /* Main result remains available if optional logs cannot be read. */ }
  finally { if (epoch === generation) logLoading.value = false }
})
function showConnectionError(error: unknown) {
  expired.value = isUnauthorized(error)
  connectionError.value = expired.value ? '登录已过期，已保留已读结果。请重新登录。'
    : '连接中断，状态可能已更新。已保留上次结果，可重试读取。'
}
async function readResult(): Promise<boolean> {
  const id = taskId.value, epoch = generation, account = auth.userInfo?.userId
  if (!account) return false
  const current = () => alive && epoch === generation && auth.userInfo?.userId === account
  loading.value = true
  try {
    const value = await getTaskDetailApi(id, true)
    if (!current()) return false
    task.value = value
    if (value.status === 'SUCCESS') {
      terminalReads++
      const result = await getTaskResultFileApi(id, true)
      if (!current()) return false
      resultFile.value = result
    }
    connectionError.value = ''; expired.value = false
    lastRead.value = new Date().toLocaleTimeString()
    return isActiveStatus(value.status) || (value.status === 'SUCCESS' && terminalReads < 5
      && (!ndviStatistics.value || !resultFile.value || ['PENDING', 'PENDING_PUBLISH', 'PUBLISHING'].includes(resultFile.value.status || '')))
  } catch (error) {
    if (!current()) return false
    showConnectionError(error)
    if (expired.value) return false
    throw error
  } finally { if (current()) loading.value = false }
}
function relogin() { auth.logout(); void router.push({ path: '/login', query: { redirect: route.fullPath } }) }
function returnOptions() {
  if (!task.value) return
  void router.push({ path: '/tasks/create', query: { mode: 'region-ndvi', imageId: task.value.imageId,
    ...(regionSnapshot.value ? { monitoringRegionId: regionSnapshot.value.regionId } : {}) } })
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

function openResultLayerInMap() {
  if (!resultFile.value?.imageId || !task.value?.taskType) return
  void router.push({ path: '/map', query: { taskId: task.value.id, imageId: resultFile.value.imageId, taskType: task.value.taskType } })
}

function taskTypeText(type: TaskType) {
  const map: Record<TaskType, string> = {
    NDVI: '植被指数',
    NDWI: '水体指数',
    CHANGE_DETECTION: '变化检测',
  }
  return map[type] || type
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
    PENDING_PUBLISH: '待发布',
    PUBLISHING: '发布中',
    PUBLISHED: '已发布',
    FAILED: '发布失败',
    PUBLISH_FAILED: '发布失败',
  }
  return map[status] || status
}

function resultStatusType(status: string) {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'FAILED' || status === 'PUBLISH_FAILED') return 'danger'
  if (['PUBLISHING', 'PENDING', 'PENDING_PUBLISH'].includes(status)) return 'warning'
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

<style scoped>
.analysis-result { max-width: 1080px; margin: 0 auto; --el-color-primary: #175b47; }
.result-summary { border-top: 3px solid #175b47; margin-bottom: 20px; }
.result-state { display: flex; align-items: center; justify-content: space-between; gap: 16px; color: #175b47; }
.result-state span,.result-note { color: #6a7a70; font-size: 13px; line-height: 1.8; }
.statistics-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; padding: 22px 0 10px; }
.statistics-grid > div { display: flex; flex-direction: column; gap: 10px; border-left: 2px solid #d8e4dc; padding-left: 20px; }
.statistics-grid span { color: #566d5d; font-size: 14px; }.statistics-grid strong { font-size: 44px; font-weight: 500; color: #173e2e; font-variant-numeric: tabular-nums; }
.statistics-grid small { font-size: 13px; color: #718477; }.result-feedback { margin-bottom: 16px; }.failure { color: #9a4e20; }
@media (max-width: 700px) { .statistics-grid { grid-template-columns: 1fr; }.result-state { align-items: flex-start; flex-direction: column; } }
</style>
