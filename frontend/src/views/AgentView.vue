<template>
  <section class="history-workspace">
    <div class="page-title"><h2>历史解读</h2><p>查看旧助手保存的执行记录、统计和依据。</p></div>
    <div class="history-layout">
      <aside class="history-list" aria-label="我的分析记录">
        <div class="history-heading"><h3>我的分析记录</h3><el-button link :loading="historyLoading" @click="loadHistory">刷新</el-button></div>
        <p v-if="historyError" role="alert">{{ historyError }}</p>
        <p v-else-if="!history.length && !historyLoading">暂无旧助手记录。</p>
        <button v-for="item in history" :key="item.executionId" type="button" class="history-item"
          :aria-pressed="selectedId === item.executionId" @click="openExecution(item.executionId)">
          <strong>{{ item.draft?.regionName || (item.task ? `任务 ${item.task.taskId}` : '区域分析') }}</strong>
          <span>{{ statusText(item.status) }}</span><small>{{ item.executionId.slice(0, 8) }}</small>
        </button>
        <div class="history-pages">
          <el-button text :disabled="historyPage <= 1 || historyLoading" @click="changeHistory(-1)">上一页</el-button>
          <span>{{ historyPage }}</span>
          <el-button text :disabled="historyPage * 10 >= historyTotal || historyLoading" @click="changeHistory(1)">下一页</el-button>
        </div>
      </aside>
      <div class="history-detail" aria-label="历史解读详情">
        <p v-if="error" role="alert">{{ error }}</p>
        <p v-if="reading" role="status">正在读取历史记录…</p>
        <template v-if="execution">
          <div class="history-heading"><h3>{{ statusText(execution.status) }}</h3><el-button :loading="reading" @click="refresh">查询最新状态</el-button></div>
          <p class="muted">执行 {{ execution.executionId }} · 版本 {{ execution.version }}</p>
          <p v-if="execution.error" role="status">{{ execution.error.message }}</p>
          <section v-if="execution.draft" class="saved-draft" aria-label="已保存草稿">
            <h4>已保存草稿</h4><dl>
              <dt>影像</dt><dd>{{ execution.draft.imageName }}</dd>
              <dt>采集日</dt><dd>{{ date(execution.draft.captureTime) }}</dd>
              <dt>监测区</dt><dd>{{ execution.draft.regionName }} · 版本 {{ execution.draft.regionVersion }}</dd>
            </dl>
          </section>
          <template v-if="execution.task">
            <p>关联任务 {{ execution.task.taskId }} · {{ taskStatusText(execution.task.status) }}</p>
            <RouterLink :to="`/tasks/${execution.task.taskId}`">打开任务详情</RouterLink>
          </template>
          <p v-else>这条记录尚未提交计算。<RouterLink to="/tasks/create?mode=region-ndvi">开始区域分析</RouterLink></p>
          <AgentEvidence v-if="execution.explanation" :result="execution.explanation" />
          <template v-else-if="rawMetrics">
            <h4>已保存的区域统计</h4>
            <AgentMetrics :statistics="rawMetrics.statistics" :coverage-ratio="rawMetrics.coverageRatio" />
          </template>
          <p v-if="metricsError" role="status">{{ metricsError }}</p>
          <AgentSteps :steps="steps" />
        </template>
        <p v-else-if="!reading">从左侧选择一条历史记录。新的区域分析可以从工作台发起。</p>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getAgentExecution, getAgentSteps, listAgentExecutions } from '@/api/agent'
import { getTaskDetailApi, getTaskResultFileApi } from '@/api/task'
import { useAuthStore } from '@/stores/auth'
import { canApplyView, UUID_PATTERN } from '@/utils/agentSession'
import { readStatistics, regionSnapshot, taskStatusText } from '@/utils/taskPresentation'
import { isUnauthorized } from '@/utils/regionAnalysis'
import AgentEvidence from '@/components/agent/AgentEvidence.vue'
import AgentMetrics from '@/components/agent/AgentMetrics.vue'
import AgentSteps from '@/components/agent/AgentSteps.vue'
import type { AgentExecution, AgentStatistics, AgentStep } from '@/types/agent'

const route = useRoute(), router = useRouter(), auth = useAuthStore()
const selectedId = computed(() => typeof route.query.executionId === 'string' && UUID_PATTERN.test(route.query.executionId) ? route.query.executionId : '')
const execution = ref<AgentExecution | null>(null), steps = ref<AgentStep[]>([])
const history = ref<AgentExecution[]>([]), historyPage = ref(1), historyTotal = ref(0)
const reading = ref(false), historyLoading = ref(false), error = ref(''), historyError = ref('')
const rawMetrics = ref<{ statistics: AgentStatistics; coverageRatio: number } | null>(null), metricsError = ref('')
let detailRead: AbortController | undefined, historyRead: AbortController | undefined
const statuses: Record<string, string> = {
  RUNNING: '已归档的处理中记录', NEEDS_INPUT: '未完成素材选择', WAITING_CONFIRMATION: '草稿未提交',
  EXPIRED: '草稿已失效', WAITING_TASK: '已关联计算任务', READY_TO_EXPLAIN: '计算已完成，未保存解读',
  EXPLAINING: '解读未完成', SUCCEEDED: '分析与解读已保存', INSUFFICIENT_EVIDENCE: '指标已保存，解读依据不足',
  FAILED: '本次操作未完成', UNSUPPORTED: '需求暂不支持', CANCELED: '分析已取消',
}
const statusText = (status: string) => statuses[status] || status
const date = (value?: string | null) => value ? new Date(value).toLocaleDateString('zh-CN') : '未记录'
function failure(cause: unknown) {
  if (isUnauthorized(cause)) auth.logout()
  return '暂时无法读取记录，请重试。'
}
async function loadHistory() {
  if (!auth.userInfo?.userId) return
  historyRead?.abort()
  const request = new AbortController()
  historyRead = request
  historyLoading.value = true
  historyError.value = ''
  try {
    const page = await listAgentExecutions(historyPage.value, request.signal)
    if (!request.signal.aborted) { history.value = page.records; historyTotal.value = page.total }
  } catch (cause) { if (!request.signal.aborted) historyError.value = failure(cause) }
  finally { if (!request.signal.aborted) historyLoading.value = false }
}
function changeHistory(delta: number) { historyPage.value += delta; void loadHistory() }
function openExecution(id: string) {
  if (selectedId.value === id) void refresh()
  else void router.replace({ path: '/agent', query: { executionId: id } })
}
async function refresh() {
  if (!selectedId.value || !auth.userInfo?.userId) return
  detailRead?.abort()
  const request = new AbortController()
  detailRead = request
  reading.value = true
  error.value = ''
  try {
    const view = await getAgentExecution(selectedId.value, request.signal)
    if (request.signal.aborted || !canApplyView(selectedId.value, execution.value, view)) return
    execution.value = view
    const trace = await getAgentSteps(view.executionId, request.signal)
    if (request.signal.aborted) return
    steps.value = trace.steps
    rawMetrics.value = null
    metricsError.value = ''
    if (view.task?.status === 'SUCCESS' && !view.explanation) {
      try {
        const [task, file] = await Promise.all([getTaskDetailApi(view.task.taskId, true), getTaskResultFileApi(view.task.taskId, true)])
        if (request.signal.aborted) return
        const metadata = JSON.parse(file.resultMetadata || 'null')
        const statistics = readStatistics(task, file)
        if (String(task.id) === view.task.taskId && task.taskType === 'NDVI' && regionSnapshot(task) && statistics
          && typeof metadata?.coverageRatio === 'number' && Number.isFinite(metadata.coverageRatio)
          && metadata.coverageRatio >= 0 && metadata.coverageRatio <= 1) {
          rawMetrics.value = { statistics, coverageRatio: metadata.coverageRatio }
        } else metricsError.value = '区域统计暂不可读，可打开关联任务核对。'
      } catch { if (!request.signal.aborted) metricsError.value = '区域统计暂不可读，可打开关联任务核对。' }
    }
  } catch (cause) { if (!request.signal.aborted) error.value = failure(cause) }
  finally { if (!request.signal.aborted) reading.value = false }
}
watch(() => auth.userInfo?.userId, () => {
  historyRead?.abort()
  history.value = []
  historyPage.value = 1
  historyTotal.value = 0
  void loadHistory()
}, { immediate: true })
watch([selectedId, () => auth.userInfo?.userId], () => {
  detailRead?.abort()
  execution.value = null
  steps.value = []
  rawMetrics.value = null
  metricsError.value = ''
  reading.value = false
  error.value = route.query.executionId && !selectedId.value ? '执行标识无效，请选择历史记录。' : ''
  void refresh()
}, { immediate: true })
onBeforeUnmount(() => { detailRead?.abort(); historyRead?.abort() })
</script>

<style scoped>
.history-workspace { max-width: 1320px; margin-inline: auto; }
.history-layout { display: grid; grid-template-columns: 240px minmax(0, 1fr); margin-top: 20px; border: 1px solid var(--work-border); border-radius: 6px; background: var(--work-panel); }
.history-list { padding: 16px; border-right: 1px solid var(--work-border); }
.history-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.history-heading h3 { margin: 0; font-size: 15px; }
.history-item { display: grid; width: 100%; gap: 5px; margin-top: 8px; padding: 11px; border: 1px solid var(--work-border); border-radius: 4px; color: var(--work-text); text-align: left; background: var(--work-inset); cursor: pointer; overflow-wrap: anywhere; }
.history-item[aria-pressed="true"] { border-color: var(--work-accent); background: var(--work-selected); }
.history-item span, .history-item small, .muted { color: var(--work-muted); font-size: 12px; }
.history-pages { display: flex; align-items: center; justify-content: space-between; margin-top: 16px; }
.history-detail { min-width: 0; padding: 22px; overflow-wrap: anywhere; }
.saved-draft { margin-block: 20px; padding: 14px; border: 1px solid var(--work-border); }
.saved-draft h4 { margin-top: 0; }
.saved-draft dl { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 8px 14px; margin-bottom: 0; }
.saved-draft dt { color: var(--work-muted); }
.saved-draft dd { margin: 0; }
@media (max-width: 700px) {
  .history-layout { grid-template-columns: minmax(0, 1fr); }
  .history-list { border-right: 0; border-bottom: 1px solid var(--work-border); max-height: 320px; overflow-y: auto; }
  .history-detail { padding: 15px; }
}
</style>
