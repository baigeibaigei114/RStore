<template>
  <section class="agent-workspace">
    <header class="workspace-heading"><div><span class="eyebrow">VEGETATION FIELDNOTES / 01</span><h2>NDVI 助手<span>从问题，到有依据的分析。</span></h2></div><span class="scope-badge">单期 · 已保存区域</span></header>
    <div class="workspace-grid">
      <aside class="session-rail" aria-label="我的分析记录">
        <button class="new-analysis" :disabled="!!busy || !!pending" type="button" @click="newAnalysis">＋ 新建分析</button>
        <div class="rail-heading"><span>我的分析记录</span><button type="button" :disabled="!!busy" @click="loadHistory()">刷新</button></div>
        <p v-if="!history.length" class="rail-empty">分析执行后会保存在这里，刷新后可继续查看。</p>
        <button v-for="item in history" :key="item.executionId" type="button" class="session-item" :class="{ selected: selectedId === item.executionId }" :disabled="!!busy" @click="openExecution(item.executionId)">
          <strong>{{ item.draft?.regionName || (item.task ? `任务 ${item.task.taskId}` : '区域分析') }}</strong><span>{{ statusText(item.status) }}</span><small>{{ item.executionId.slice(0, 8) }}</small>
        </button>
        <div class="history-pages"><button type="button" :disabled="historyPage <= 1 || !!busy" @click="changeHistory(-1)">上一页</button><span>{{ historyPage }}</span><button type="button" :disabled="historyPage * 10 >= historyTotal || !!busy" @click="changeHistory(1)">下一页</button></div>
        <div class="rail-note"><span class="leaf-mark">◒</span><p>只解释这一期、这个区域。<br>不凭单期均值判断作物健康。</p></div>
      </aside>

      <main class="analysis-sheet" aria-label="NDVI助手工作区">
        <div class="mode-switch" role="group" aria-label="助手模式"><button type="button" :aria-pressed="mode === 'analysis'" :disabled="!!busy" @click="mode = 'analysis'">区域分析</button><button type="button" :aria-pressed="mode === 'knowledge'" :disabled="!!busy" @click="mode = 'knowledge'">方法问答</button></div>
        <div v-if="pending" class="pending-notice" role="status"><strong>有一个请求的结果尚未确认</strong><p>请先查询原状态，或使用原请求继续。刷新页面不会重新发送。</p><button v-if="pending.kind !== 'create'" type="button" :disabled="!!busy" @click="openExecution(pending.executionId)">查询原执行</button><button type="button" :disabled="!!busy" @click="repeatPending">继续原请求</button></div>
        <p v-if="error" class="request-error" role="alert">{{ error }}</p>
        <p v-if="busy" class="working" role="status">{{ busy }}<span class="working-dot"> ···</span></p>

        <template v-if="mode === 'knowledge'">
          <div class="section-title"><span>METHOD NOTES</span><h3>先理解指标，再读结果。</h3><p>询问计算方法、有效像素、区域口径与解释限制。</p></div>
          <form class="intent-form" @submit.prevent="askQuestion"><label for="knowledge-question">你的问题</label><textarea id="knowledge-question" v-model="question" maxlength="2000" rows="3" :disabled="!!busy" placeholder="有效像素比例和植被覆盖率有什么区别？" /><div class="form-footer"><span>仅查询公开方法资料</span><button type="submit" class="primary" :disabled="!!busy || !question.trim()">提问并查看依据 ↗</button></div></form>
          <AgentEvidence v-if="answer" :result="answer" />
        </template>

        <template v-else-if="!execution">
          <div class="section-title welcome"><div class="field-symbol" aria-hidden="true"><i v-for="n in 16" :key="n" /></div><span>START WITH A REGION</span><h3>你想了解哪个区域？</h3><p>描述需求，选择可用影像与已保存区域。<br>核对草稿后，再提交一次计算。</p></div>
          <form class="intent-form" @submit.prevent="createExecution"><label for="analysis-goal">分析需求</label><textarea id="analysis-goal" v-model="goal" maxlength="2000" rows="4" :disabled="!!busy || !!pending" placeholder="查找我可用的标准化影像和已保存区域，分析单期 NDVI。" /><div class="form-footer"><span>提交前会展示影像、区域和参数</span><button type="submit" class="primary" :disabled="!!busy || !!pending || !goal.trim()">整理分析需求 ↗</button></div></form>
          <div class="workflow-guide"><span><b>01</b> 选择影像与区域</span><span><b>02</b> 核对并确认计算</span><span><b>03</b> 查看统计与依据</span></div>
        </template>

        <template v-else>
          <div class="execution-heading"><div><span class="eyebrow">ANALYSIS RECORD</span><h3>{{ statusText(execution.status) }}</h3></div><button type="button" :disabled="!!busy || reading" @click="refresh(true)">查询最新状态</button></div>
          <p class="execution-id">执行 {{ execution.executionId }} · 版本 {{ execution.version }}</p>
          <p v-if="pollPaused" class="muted">自动刷新已暂停。可手动查询最新状态。</p>
          <p v-if="execution.question" class="agent-question">{{ execution.question }}</p>
          <p v-if="execution.unsupportedReason" class="state-note">{{ execution.unsupportedReason }}</p>
          <div v-if="execution.error" class="state-error" role="alert"><strong>{{ execution.error.message }}</strong><small>{{ execution.error.errorCode }}</small><button v-if="execution.error.retryable" type="button" :disabled="!!busy || !!pending" @click="retryExecution">显式重试本次操作</button><p v-if="execution.task && execution.error.retryable">已关联任务 {{ execution.task.taskId }}，重试解释不会重新计算。</p><p v-else-if="execution.task?.status === 'FAILED'">计算失败时不生成成功解释，可从任务详情查看原因。</p></div>

          <form v-if="canMessage" class="selection-form" @submit.prevent="sendMessage">
            <div v-if="execution.imageCandidates.length || execution.regionCandidates.length" class="candidate-grid">
              <div><label for="image-choice">可用影像</label><select id="image-choice" v-model="selectedImage" :disabled="!!busy || !!pending"><option value="">请选择影像</option><option v-for="item in execution.imageCandidates" :key="item.id" :value="item.id">{{ item.name }} · {{ item.sensor || '传感器未标注' }} · {{ date(item.captureTime) }}</option></select></div>
              <div><label for="region-choice">已保存区域</label><select id="region-choice" v-model="selectedRegion" :disabled="!!busy || !!pending"><option value="">请选择区域</option><option v-for="item in execution.regionCandidates" :key="item.id" :value="item.id">{{ item.name }} · 版本 {{ item.version }}</option></select></div>
            </div>
            <p v-if="execution.hasMoreCandidates" class="muted">还有更多候选，请补充名称或日期缩小范围。</p>
            <label for="analysis-extra">补充需求</label><textarea id="analysis-extra" v-model="extra" maxlength="2000" rows="2" :disabled="!!busy || !!pending" placeholder="可补充影像名称、时间或区域名称，或直接从列表选择。" /><button type="submit" :disabled="!!busy || !!pending || (!extra.trim() && !selectedImage && !selectedRegion)">提交选择 / 补充</button>
          </form>

          <section v-if="execution.draft" class="draft-card" aria-label="待确认计算草稿"><div class="draft-label">PLEASE REVIEW / 请核对本次计算</div><dl><div><dt>影像</dt><dd>{{ execution.draft.imageName }}</dd></div><div><dt>采集日期</dt><dd>{{ date(execution.draft.captureTime) }}</dd></div><div><dt>监测区域</dt><dd>{{ execution.draft.regionName }} · 版本 {{ execution.draft.regionVersion }}</dd></div><div><dt>计算类型</dt><dd>单期区域 NDVI · 红光波段 {{ execution.draft.params.redBand }} / 近红外波段 {{ execution.draft.params.nirBand }}</dd></div></dl><p>有效至 {{ dateTime(execution.draft.expiresAt) }}。确认后将创建一个计算任务。</p><button type="button" class="primary" :disabled="!!busy || !!pending" @click="confirmDraft">确认并提交计算</button></section>
          <p v-if="execution.status === 'EXPIRED'" class="state-note">草稿已过期或资源已变化。请补充需求，重新整理后确认。</p>
          <p v-if="execution.status === 'CANCELED'" class="state-note">此次未提交的分析已取消，可新建需求。</p>

          <section v-if="execution.task" class="task-card" aria-label="已关联计算任务"><div><strong>任务 {{ execution.task.taskId }}</strong><span>{{ execution.task.status }} · {{ execution.task.progress }}%</span></div><progress :value="execution.task.progress" max="100" /><RouterLink :to="`/tasks/${execution.task.taskId}`">查看任务详情 ↗</RouterLink><p v-if="execution.status === 'WAITING_TASK'">正在等待遥感计算。查询进度不会调用模型。</p></section>
          <section v-if="rawMetrics && !execution.explanation" aria-label="计算已保存的指标"><h4>已保存的区域统计</h4><AgentMetrics :statistics="rawMetrics.statistics" :coverage-ratio="rawMetrics.coverageRatio" /><p class="muted">指标来自任务 {{ execution.task?.taskId }}，方法解释尚未生成。</p></section>
          <p v-if="metricsError && !execution.explanation" class="muted" role="status">{{ metricsError }}</p>
          <button v-if="execution.status === 'READY_TO_EXPLAIN'" type="button" class="primary explain-button" :disabled="!!busy || !!pending" @click="explain">生成带引用的方法解释 ↗</button>
          <AgentEvidence v-if="execution.explanation" :result="execution.explanation" />
          <p class="budget-note">已使用 {{ execution.budget.modelCalls }} 次模型调用 · {{ execution.budget.toolCalls }} 次工具查询</p>
          <AgentSteps :steps="steps" />
          <button v-if="canCancel" type="button" class="cancel-button" :disabled="!!busy || !!pending" @click="cancelExecution">取消此次未提交分析</button>
        </template>
      </main>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import { ApiError } from '@/api/request'
import { answerAgentQuestion, executeAgentAction, getAgentExecution, getAgentSteps, listAgentExecutions } from '@/api/agent'
import { getTaskResultFileApi } from '@/api/task'
import { useAuthStore } from '@/stores/auth'
import type { AgentAnswer, AgentExecution, AgentStatistics, AgentStatus, AgentStep, PendingAction } from '@/types/agent'
import { canApplyView, PendingActionStore, RequestEpoch, resolvedByQuery, shouldPoll, UUID_PATTERN } from '@/utils/agentSession'
import AgentEvidence from '@/components/agent/AgentEvidence.vue'
import AgentMetrics from '@/components/agent/AgentMetrics.vue'
import AgentSteps from '@/components/agent/AgentSteps.vue'

const route = useRoute(), router = useRouter(), auth = useAuthStore()
const mode = ref<'analysis' | 'knowledge'>('analysis'), goal = ref(''), question = ref(''), extra = ref('')
const execution = ref<AgentExecution | null>(null), answer = ref<AgentAnswer | null>(null), steps = ref<AgentStep[]>([])
const history = ref<AgentExecution[]>([]), historyPage = ref(1), historyTotal = ref(0)
const selectedId = ref(''), selectedImage = ref(''), selectedRegion = ref(''), ownerId = ref('')
const pending = ref<PendingAction | null>(null), busy = ref(''), error = ref(''), reading = ref(false), pollPaused = ref(false)
const rawMetrics = ref<{ statistics: AgentStatistics; coverageRatio: number } | null>(null)
const metricsError = ref('')
const epoch = new RequestEpoch()
let store: PendingActionStore | null = null, timer: ReturnType<typeof setTimeout> | undefined, polls = 0
let reads: AbortController | null = null, writes: AbortController | null = null
let historyVersion = 0
const canMessage = computed(() => !!execution.value && !execution.value.task && ['NEEDS_INPUT', 'WAITING_CONFIRMATION', 'EXPIRED'].includes(execution.value.status))
const canCancel = computed(() => !!execution.value && !execution.value.task && !['UNSUPPORTED', 'CANCELED'].includes(execution.value.status))
const statuses: Record<AgentStatus, string> = { RUNNING: '正在整理需求', NEEDS_INPUT: '等待选择或补充', WAITING_CONFIRMATION: '请确认计算草稿', EXPIRED: '草稿已失效', WAITING_TASK: '计算任务已提交', READY_TO_EXPLAIN: '计算成功，等待解释', EXPLAINING: '正在生成方法解释', SUCCEEDED: '分析与解释已保存', INSUFFICIENT_EVIDENCE: '指标已保存，解释依据不足', FAILED: '本次操作未完成', UNSUPPORTED: '暂不支持此类需求', CANCELED: '分析已取消' }
function statusText(status: AgentStatus) { return statuses[status] || status }
function date(value?: string | null) { if (!value) return '未标注'; const parsed = new Date(value); return Number.isNaN(parsed.getTime()) ? '未标注' : parsed.toLocaleDateString('zh-CN') }
function dateTime(value: string) { const parsed = new Date(value); return Number.isNaN(parsed.getTime()) ? '未标注' : parsed.toLocaleString('zh-CN') }
function stopPoll() { clearTimeout(timer); timer = undefined }
function clearPending() { store?.clear(); pending.value = null }
function resetContext() { epoch.next(); stopPoll(); reads?.abort(); writes?.abort(); reads = null; writes = null; reading.value = false; busy.value = ''; polls = 0; pollPaused.value = false }
function failure(cause: unknown, token: number) {
  if (!epoch.accepts(token)) return
  const code = cause instanceof ApiError ? cause.code : isAxiosError(cause) ? cause.response?.status : undefined
  if (code === 401) { auth.logout(); void router.replace('/login'); return }
  error.value = isAxiosError(cause) ? '网络或服务暂时未响应，请查询原状态后再继续' : cause instanceof Error ? cause.message : '请求未完成，请查询原状态后再继续'
}
async function loadHistory() {
  const token = epoch.current()
  const version = ++historyVersion
  if (!ownerId.value) return
  try { const page = await listAgentExecutions(historyPage.value); if (epoch.accepts(token) && version === historyVersion) { history.value = page.records; historyTotal.value = page.total } }
  catch (cause) { if (version === historyVersion) failure(cause, token) }
}
function changeHistory(delta: number) { historyPage.value += delta; void loadHistory() }
async function openExecution(id: string) { mode.value = 'analysis'; if (selectedId.value === id) await refresh(true); else await router.replace({ path: '/agent', query: { executionId: id } }) }
async function newAnalysis() { mode.value = 'analysis'; goal.value = ''; await router.replace({ path: '/agent' }); if (!selectedId.value) { execution.value = null; error.value = '' } }
function applyView(view: AgentExecution) {
  if (!canApplyView(selectedId.value, execution.value, view)) return
  const newDraft = execution.value?.draft?.draftHash !== view.draft?.draftHash
  const newExecution = execution.value?.executionId !== view.executionId
  if (newExecution || !view.task || view.task.status !== 'SUCCESS' || ['STATISTICS_MISSING', 'STATISTICS_INVALID'].includes(view.error?.errorCode || '')) { rawMetrics.value = null; metricsError.value = '' }
  execution.value = view
  if (newExecution || newDraft || !view.imageCandidates.some(item => item.id === selectedImage.value) || !view.regionCandidates.some(item => item.id === selectedRegion.value)) {
    selectedImage.value = view.draft?.imageId || (view.imageCandidates.length === 1 ? view.imageCandidates[0]!.id : '')
    selectedRegion.value = view.draft?.monitoringRegionId || (view.regionCandidates.length === 1 ? view.regionCandidates[0]!.id : '')
  }
  if (pending.value && resolvedByQuery(pending.value, view)) clearPending()
  const index = history.value.findIndex(item => item.executionId === view.executionId)
  if (index >= 0) history.value[index] = view
}
async function readMetrics(token: number, view: AgentExecution) {
  if (!view.task || view.task.status !== 'SUCCESS' || view.explanation || ['STATISTICS_MISSING', 'STATISTICS_INVALID'].includes(view.error?.errorCode || '')) return
  try {
    const file = await getTaskResultFileApi(view.task.taskId, true)
    if (!epoch.accepts(token) || selectedId.value !== view.executionId) return
    metricsError.value = '暂未读取到可展示的区域统计，可打开任务详情核对。'
    if (!file?.resultMetadata) return
    const metadata = JSON.parse(file.resultMetadata) as { schemaVersion?: number; scope?: string; statistics?: AgentStatistics; coverageRatio?: number }
    if (String(file.taskId) === view.task.taskId && metadata.schemaVersion === 2 && metadata.scope === 'monitoring_region' && metadata.statistics
      && ['mean', 'min', 'max', 'totalPixelCount', 'validPixelCount', 'invalidPixelCount', 'validPixelRatio'].every(key => typeof metadata.statistics?.[key as keyof AgentStatistics] === 'number' && Number.isFinite(metadata.statistics[key as keyof AgentStatistics]))
      && typeof metadata.coverageRatio === 'number' && Number.isFinite(metadata.coverageRatio)) { rawMetrics.value = { statistics: metadata.statistics, coverageRatio: metadata.coverageRatio }; metricsError.value = '' }
  } catch { if (epoch.accepts(token) && selectedId.value === view.executionId) { rawMetrics.value = null; metricsError.value = '暂未读取到区域统计，可查询最新状态或打开任务详情核对。' } }
}
function schedulePoll() {
  stopPoll()
  if (!shouldPoll(execution.value) || document.hidden || busy.value) return
  if (polls >= 60) { pollPaused.value = true; return }
  timer = setTimeout(() => { polls++; void refresh(false) }, 3000)
}
async function refresh(manual = false) {
  if (!selectedId.value || reading.value || busy.value) return
  if (manual) { polls = 0; pollPaused.value = false; error.value = '' }
  stopPoll(); reads?.abort(); reads = new AbortController(); const controller = reads, signal = controller.signal
  const token = epoch.current(), id = selectedId.value; reading.value = true
  try {
    const view = await getAgentExecution(id, signal)
    if (!epoch.accepts(token) || signal.aborted || reads !== controller) return
    applyView(view)
    const trace = await getAgentSteps(id, signal)
    if (!epoch.accepts(token) || signal.aborted || reads !== controller) return
    steps.value = trace.steps
    await readMetrics(token, view)
    if (epoch.accepts(token)) schedulePoll()
  } catch (cause) { if (!signal.aborted) { failure(cause, token); if (epoch.accepts(token)) pollPaused.value = true } }
  finally { if (epoch.accepts(token) && reads === controller) reading.value = false }
}
async function perform(action: PendingAction) {
  if (busy.value || !store || !ownerId.value) return
  const token = epoch.current(); error.value = ''; stopPoll(); reads?.abort(); reads = null; reading.value = false
  try { store.save(action); pending.value = action } catch { error.value = '无法保存此次请求，请检查浏览器会话存储后再提交'; return }
  busy.value = action.kind === 'confirm' ? '正在提交计算' : '正在处理分析请求'; writes = new AbortController()
  try {
    const view = await executeAgentAction(action, writes.signal)
    if (!epoch.accepts(token)) return
    clearPending(); selectedId.value = view.executionId; applyView(view); extra.value = ''
    await router.replace({ path: '/agent', query: { executionId: view.executionId } })
    if (epoch.accepts(token)) { busy.value = ''; await loadHistory(); await refresh(false) }
  } catch (cause) {
    if (!epoch.accepts(token)) return
    if (cause instanceof ApiError && cause.code < 500) clearPending()
    failure(cause, token)
    // Version/expiry rejection may have committed a new server state. Read it without replaying the write.
    if (cause instanceof ApiError && cause.code === 409 && action.kind !== 'create' && selectedId.value === action.executionId) {
      busy.value = ''; await refresh(false); if (epoch.accepts(token)) await loadHistory()
    }
  } finally { if (epoch.accepts(token)) busy.value = '' }
}
function createExecution() { if (goal.value.trim() && !pending.value) void perform({ kind: 'create', body: { clientRequestId: crypto.randomUUID(), text: goal.value.trim() } }) }
function sendMessage() {
  if (!execution.value || pending.value) return
  const body = { clientRequestId: crypto.randomUUID(), expectedVersion: execution.value.version,
    ...(extra.value.trim() ? { text: extra.value.trim() } : {}), ...(selectedImage.value ? { selectedImageId: selectedImage.value } : {}), ...(selectedRegion.value ? { selectedRegionId: selectedRegion.value } : {}) }
  void perform({ kind: 'message', executionId: execution.value.executionId, body })
}
function confirmDraft() { if (execution.value?.draft && !pending.value) void perform({ kind: 'confirm', executionId: execution.value.executionId, body: { draftVersion: execution.value.draft.draftVersion, draftHash: execution.value.draft.draftHash } }) }
function explain() { if (execution.value && !pending.value) void perform({ kind: 'explanation', executionId: execution.value.executionId, body: { clientRequestId: crypto.randomUUID() } }) }
function retryExecution() { if (execution.value && !pending.value) void perform({ kind: 'retry', executionId: execution.value.executionId, body: { clientRequestId: crypto.randomUUID(), expectedVersion: execution.value.version } }) }
function cancelExecution() { if (execution.value && !pending.value) void perform({ kind: 'cancel', executionId: execution.value.executionId, body: { expectedVersion: execution.value.version } }) }
async function repeatPending() { const saved = pending.value; if (!saved || busy.value) return; if (saved.kind !== 'create' && saved.executionId !== selectedId.value) { await router.replace({ path: '/agent', query: { executionId: saved.executionId } }); await nextTick() } mode.value = 'analysis'; await perform(saved) }
async function askQuestion() {
  if (busy.value || !question.value.trim()) return
  const token = epoch.current(); error.value = ''; busy.value = '正在查询方法资料'; writes = new AbortController(); answer.value = null; stopPoll()
  try { const value = await answerAgentQuestion(question.value.trim(), writes.signal); if (epoch.accepts(token)) answer.value = value }
  catch (cause) { failure(cause, token) }
  finally { if (epoch.accepts(token)) { busy.value = ''; schedulePoll() } }
}
function visibility() { if (document.hidden) { stopPoll(); reads?.abort(); reads = null; reading.value = false } else { polls = 0; void refresh(false) } }
watch(() => [auth.userInfo?.userId, route.query.executionId] as const, ([owner, requested]) => {
  const user = owner === undefined || owner === null ? '' : String(owner)
  const id = typeof requested === 'string' && UUID_PATTERN.test(requested) ? requested : ''
  if (user === ownerId.value && id === selectedId.value) return
  resetContext(); const accountChanged = user !== ownerId.value; ownerId.value = user; selectedId.value = id
  execution.value = null; steps.value = []; rawMetrics.value = null; metricsError.value = ''; selectedImage.value = ''; selectedRegion.value = ''; extra.value = ''; error.value = ''
  if (accountChanged) { answer.value = null; history.value = []; historyPage.value = 1; historyTotal.value = 0; goal.value = ''; question.value = '' }
  try { store = user ? new PendingActionStore(sessionStorage, user) : null; pending.value = store?.read() || null }
  catch { store = null; pending.value = null; error.value = '无法访问会话存储，暂不能提交新请求；仍可查询已有分析。' }
  if (requested && !id) error.value = '执行标识无效，请从左侧选择分析记录'
  if (user) { void loadHistory(); if (id) void refresh(false) }
}, { immediate: true })
document.addEventListener('visibilitychange', visibility)
onBeforeUnmount(() => { resetContext(); document.removeEventListener('visibilitychange', visibility) })
</script>

<style scoped>
.agent-workspace { --ink: #253f31; --green: #3a654b; --line: #dbe2d5; color: var(--ink); max-width: 1440px; margin: 0 auto; }
.workspace-heading { display: flex; justify-content: space-between; align-items: flex-end; padding: 8px 0 25px; gap: 16px; }.eyebrow { font-size: 10px; font-weight: 600; letter-spacing: .16em; color: #74826e; }
.workspace-heading h2 { font-family: 'Noto Serif SC', 'Songti SC', SimSun, Georgia, serif; font-size: 30px; font-weight: 500; margin: 10px 0 0; letter-spacing: .03em; }.workspace-heading h2 span { font-family: 'Microsoft YaHei', sans-serif; font-size: 13px; color: #718071; font-weight: 400; margin-left: 20px; letter-spacing: 0; }
.scope-badge { font-size: 11px; color: #59734e; padding: 7px 13px; background: #e9efdf; white-space: nowrap; border: 1px solid #d9e1cd; border-radius: 30px; }
.workspace-grid { display: grid; grid-template-columns: 218px minmax(0, 1fr); min-height: 620px; border: 1px solid var(--line); background: #fcfcf7; border-radius: 4px; box-shadow: 0 4px 20px #223a2310; }
.session-rail { background: #f0f3e9; padding: 22px 16px; border-right: 1px solid var(--line); display: flex; flex-direction: column; }.new-analysis { background: #2d503c; color: #fff; border: none; padding: 12px; text-align: left; }
button { font: inherit; cursor: pointer; border-radius: 3px; background: transparent; border: 1px solid #bdcbb6; color: #35583f; padding: 8px 12px; font-size: 12px; transition: background .15s; }button:disabled { cursor: not-allowed; opacity: .5; }button:not(:disabled):hover { background: #e4ebda; }button.primary, .new-analysis { background: #2d503c; color: #fff; border-color: #2d503c; }button.primary:not(:disabled):hover, .new-analysis:not(:disabled):hover { background: #40684c; }button:focus-visible, textarea:focus-visible, select:focus-visible { outline: 2px solid #8aa46d; outline-offset: 3px; }
.rail-heading { display: flex; align-items: center; justify-content: space-between; margin-top: 28px; font-size: 11px; color: #75816d; }.rail-heading button { border: 0; padding: 4px; font-size: 10px; }.rail-empty { font-size: 12px; color: #8a9380; line-height: 1.8; }.session-item { border: 0; border-left: 2px solid transparent; border-radius: 0; text-align: left; margin: 6px -2px 0; padding: 10px 12px; display: grid; gap: 5px; }.session-item strong { font-size: 12px; font-weight: 500; overflow-wrap: anywhere; }.session-item span, .session-item small { font-size: 10px; color: #829077; }.session-item.selected { border-left-color: #6d8e58; background: #e0e8d7; }.history-pages { display: flex; align-items: center; justify-content: space-between; margin-top: 15px; color: #8a9380; font-size: 10px; }.history-pages button { border: 0; padding: 5px 0; font-size: 10px; }.rail-note { margin-top: auto; padding-top: 32px; font-size: 11px; line-height: 1.8; color: #8a947d; }.leaf-mark { font-size: 25px; color: #a1b08c; }
.analysis-sheet { padding: 26px 36px 32px; min-width: 0; }.mode-switch { display: flex; gap: 20px; border-bottom: 1px solid var(--line); margin-bottom: 28px; }.mode-switch button { border: 0; border-radius: 0; padding: 8px 2px 13px; color: #899280; font-size: 12px; }.mode-switch button[aria-pressed='true'] { border-bottom: 2px solid var(--green); color: var(--ink); }.section-title > span { font-size: 10px; letter-spacing: .12em; color: #8e9c81; }.section-title h3 { font-family: 'Noto Serif SC', 'Songti SC', SimSun, serif; font-size: 27px; font-weight: 400; margin: 10px 0 14px; }.section-title p { font-size: 13px; color: #7e8977; line-height: 1.9; }.welcome { padding-top: 10px; }.field-symbol { display: grid; grid-template-columns: repeat(4, 9px); gap: 3px; margin-bottom: 20px; transform: rotate(-5deg); width: max-content; }.field-symbol i { height: 9px; background: #c6d2ae; }.field-symbol i:nth-child(3n) { background: #8aab69; }.field-symbol i:nth-child(5n) { background: #4c7c49; }
.intent-form { margin: 26px 0; }label { display: block; font-size: 12px; margin-bottom: 9px; color: #526c4b; }textarea, select { width: 100%; color: #314a36; background: #fffefb; border: 1px solid #ccd7c2; border-radius: 3px; padding: 13px; font: inherit; font-size: 13px; line-height: 1.8; resize: vertical; }textarea::placeholder { color: #a1aa98; }select { min-height: 46px; text-overflow: ellipsis; }.form-footer { display: flex; gap: 12px; justify-content: space-between; align-items: center; margin-top: 12px; }.form-footer span { font-size: 11px; color: #87937d; }.workflow-guide { display: flex; gap: 25px; border-top: 1px solid var(--line); padding-top: 22px; margin-top: 36px; font-size: 11px; color: #7e8977; }.workflow-guide b { font-family: Georgia, serif; color: #9cac8c; margin-right: 6px; }
.pending-notice { background: #f9f0d7; padding: 15px; margin-bottom: 20px; font-size: 12px; border-left: 3px solid #b99851; }.pending-notice p { line-height: 1.8; }.pending-notice button { margin-right: 10px; }.request-error, .state-error { padding: 13px 16px; color: #8d4235; background: #faf0e9; border-left: 3px solid #bd8a72; font-size: 13px; line-height: 1.8; }.working { color: #6d855e; font-size: 12px; }.working-dot { animation: pulse 1.5s ease-in-out infinite; }.execution-heading { display: flex; justify-content: space-between; align-items: center; gap: 12px; }.execution-heading h3 { font-size: 22px; font-weight: 500; margin: 8px 0; }.execution-id { color: #95a08b; font-size: 10px; overflow-wrap: anywhere; }.agent-question { font-size: 15px; padding: 15px 0; line-height: 1.8; }.selection-form { padding: 20px 0; border-bottom: 1px solid var(--line); }.candidate-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 18px; }.selection-form > button { margin-top: 10px; }.draft-card { border: 1px solid #c9d6ba; background: #f0f4e9; padding: 24px; margin: 24px 0; }.draft-label { font-size: 10px; letter-spacing: .08em; color: #6b815a; }.draft-card dl > div { display: grid; grid-template-columns: 80px 1fr; margin: 12px 0; gap: 10px; font-size: 13px; line-height: 1.7; }.draft-card dt { color: #7c8b70; }.draft-card dd { margin: 0; overflow-wrap: anywhere; }.draft-card p { font-size: 11px; color: #87937b; }.task-card { border: 1px solid var(--line); padding: 20px; margin: 22px 0; }.task-card > div { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; }.task-card span, .task-card p { font-size: 12px; color: #859279; }.task-card progress { display: block; width: 100%; height: 6px; margin: 16px 0; accent-color: #6c8c51; }.task-card a { font-size: 12px; color: #355f42; }.state-error small { display: block; font-size: 10px; }.state-error button { margin-top: 10px; }.state-note { background: #f0f3e9; padding: 16px; font-size: 13px; line-height: 1.8; }.muted, .budget-note { color: #859279; font-size: 11px; line-height: 1.8; }.budget-note { margin-top: 28px; }.cancel-button { margin-top: 20px; border: 0; color: #9a8973; padding-left: 0; }.explain-button { margin: 5px 0 24px; }h4 { font-size: 14px; font-weight: 500; }
@keyframes pulse { 50% { opacity: .3; } }@media (prefers-reduced-motion: reduce) { .working-dot { animation: none; } }
@media (max-width: 1050px) { .workspace-grid { grid-template-columns: 170px minmax(0, 1fr); }.analysis-sheet { padding: 24px; }.workspace-heading h2 span { display: block; margin: 8px 0 0; } }
@media (max-width: 760px) { .workspace-heading { align-items: center; }.workspace-heading h2 { font-size: 24px; }.workspace-grid { grid-template-columns: 1fr; }.session-rail { border-right: 0; border-bottom: 1px solid var(--line); padding: 16px; }.session-item { display: inline-grid; }.rail-note { display: none; }.history-pages { max-width: 200px; }.rail-heading { margin-top: 16px; }.analysis-sheet { padding: 20px 16px; }.candidate-grid { grid-template-columns: 1fr; }.form-footer { align-items: flex-start; flex-direction: column; }.workflow-guide { gap: 12px; flex-wrap: wrap; }.scope-badge { display: none; } }
</style>
