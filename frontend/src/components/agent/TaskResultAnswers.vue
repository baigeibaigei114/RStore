<template>
  <section class="result-answers" aria-labelledby="result-answers-title">
    <h3 id="result-answers-title">问这次结果</h3>
    <div v-if="state?.historyVersions.length" class="result-answer-actions">
      <label for="result-answer-version">对话版本</label>
      <select id="result-answer-version" v-model="selectedVersion" :disabled="busy || !!pending" @change="changeVersion">
        <option value="">当前结果</option>
        <option v-for="(version, index) in state.historyVersions" :key="version" :value="version">
          历史结果 {{ index + 1 }}
        </option>
      </select>
    </div>
    <p v-if="feedback" role="status">{{ feedback }}</p>
    <p v-else-if="state?.message">{{ state.message }}</p>
    <p v-if="selectedVersion">正在查看历史对话。切回当前结果可以继续提问。</p>
    <el-button v-if="state?.hasEarlier" :disabled="busy || !!pending" @click="refresh(true)">加载更早的问答</el-button>
    <div aria-live="polite">
      <article v-for="entry in turns" :key="entry.requestId" class="saved-answer">
        <strong>{{ entry.question }}</strong>
        <p v-if="entry.status === 'RUNNING'">正在解读，稍后可查询回答。</p>
        <p v-else-if="entry.status === 'FAILED'">{{ entry.error || '本次解读未完成，可以重新提问。' }}</p>
        <template v-else-if="entry.answer">
          <p>{{ entry.answer.statement }}</p>
          <details v-if="entry.answer.evidence.length || entry.answer.partitions?.length" :open="entry.requestId === turn?.requestId">
            <summary>查看数据依据</summary>
            <p>本次任务 · 区域版本 {{ entry.answer.regionVersion }}</p>
            <dl>
              <div v-for="item in entry.answer.evidence" :key="item.key">
                <dt>{{ item.label }}</dt>
                <dd>{{ formatValue(item.value, item.unit) }}</dd>
              </div>
            </dl>
            <ul v-if="entry.answer.partitions?.length" class="partitions">
              <li v-for="part in entry.answer.partitions" :key="part.id">
                <strong>{{ sectionLabels[part.id] }}分区</strong>
                <span>均值 {{ part.mean == null ? '暂无有效像素' : formatValue(part.mean, 'decimal') }}</span>
                <span>有效像素 {{ formatValue(part.valid, 'count') }} / {{ formatValue(part.total, 'count') }}</span>
                <el-button v-if="part.locatable && mapReady && entry.resultVersion === state?.currentVersion" link type="primary"
                  @click="openPartition(entry.resultVersion, part.id)">在地图高亮{{ sectionLabels[part.id] }}分区</el-button>
                <small v-else>该分区的地图范围暂不可用</small>
              </li>
            </ul>
          </details>
        </template>
      </article>
    </div>
    <form v-if="state?.canAsk" @submit.prevent="submit">
      <div class="result-answer-actions">
        <el-button v-for="example in examples" :key="example" :disabled="busy || !!pending || turn?.status === 'RUNNING'"
          @click="question = example">{{ example }}</el-button>
      </div>
      <label for="result-answer-question">你的问题</label>
      <el-input id="result-answer-question" v-model="question" maxlength="500"
        :disabled="busy || !!pending || turn?.status === 'RUNNING'"
        placeholder="例如，本次区域 NDVI 的均值和数值范围如何？" />
      <div class="result-answer-actions">
        <el-button v-if="!pending" type="primary" native-type="submit" :loading="busy"
          :disabled="!question.trim() || turn?.status === 'RUNNING'">提问</el-button>
        <el-button v-else-if="turn?.status !== 'RUNNING'" type="primary" :loading="busy" @click="submit">重试本次请求</el-button>
        <el-button v-if="pending || turn?.status === 'RUNNING'" :disabled="busy" @click="refresh()">查询回答</el-button>
      </div>
    </form>
    <el-button v-if="!state && feedback" :disabled="busy" @click="refresh()">重新加载</el-button>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { askTaskResultApi, getTaskResultAnswerApi } from '@/api/ai'
import { ApiError } from '@/api/request'
import type { ResultSection, TaskResultAnswer, TaskResultAnswerState, TaskResultQuestion } from '@/types/ai'
import { isUnauthorized } from '@/utils/regionAnalysis'
import { sectionLabels } from '@/utils/resultEvidence'

const props = defineProps<{ taskId: string; mapReady: boolean }>()
const router = useRouter()
const examples = ['本次区域 NDVI 整体如何？', '有效像素比例是多少？', '哪个分区均值最高？', '四个分区的数值有什么差异？']
const question = ref('')
const state = ref<TaskResultAnswerState | null>(null)
const turns = ref<TaskResultAnswer[]>([])
const turn = computed(() => turns.value.at(-1))
const selectedVersion = ref('')
const pending = ref<TaskResultQuestion | null>(null)
const feedback = ref('')
const busy = ref(false)
const lifetime = new AbortController()

function openPartition(resultVersion: string, section: ResultSection) {
  void router.push({ path: '/map', query: { taskId: props.taskId, resultVersion, section } })
}

function resolvePending(value?: TaskResultAnswer) {
  if (value && value.requestId === pending.value?.requestId && value.status !== 'RUNNING') {
    pending.value = null
    if (value.status === 'COMPLETE') question.value = ''
  }
}

function invalidate() {
  state.value = null
  turns.value = []
  pending.value = null
}

function stale(error: unknown) {
  return isUnauthorized(error) || (error instanceof ApiError && [404, 409].includes(error.code))
}

function changeVersion() {
  turns.value = []
  if (state.value) state.value.canAsk = false
  void refresh()
}

async function refresh(earlier = false) {
  if (busy.value) return
  const beforeTurn = earlier ? turns.value[0]?.number : undefined
  if (earlier && !beforeTurn) return
  busy.value = true
  feedback.value = ''
  try {
    const version = earlier ? state.value?.resultVersion : selectedVersion.value
    const data = await getTaskResultAnswerApi(props.taskId, lifetime.signal, version || undefined, beforeTurn)
    if (lifetime.signal.aborted) return
    if (earlier) {
      if (state.value) {
        state.value.hasEarlier = data.hasEarlier
        state.value.canAsk = data.canAsk
        state.value.historyVersions = data.historyVersions
      }
      turns.value = [...data.turns, ...turns.value]
      if (!data.canAsk && data.resultVersion) selectedVersion.value = data.resultVersion
      return
    }
    state.value = data
    turns.value = data.turns
    resolvePending(turns.value.find(item => item.requestId === pending.value?.requestId))
    if (pending.value && (!data.canAsk || data.resultVersion !== pending.value.resultVersion)) pending.value = null
  } catch (error) {
    if (lifetime.signal.aborted) return
    if (stale(error)) invalidate()
    feedback.value = isUnauthorized(error) ? '登录已失效，请重新登录'
      : error instanceof ApiError ? error.message : '暂时无法读取解读状态'
  } finally { busy.value = false }
}

async function submit() {
  if (busy.value || !state.value?.canAsk || !state.value.resultVersion || turn.value?.status === 'RUNNING' ||
    (!pending.value && !question.value.trim())) return
  const body = pending.value ?? { requestId: crypto.randomUUID(), resultVersion: state.value.resultVersion,
    question: question.value.trim(), expectedTurn: turn.value?.number ?? 0 }
  pending.value = body
  busy.value = true
  feedback.value = ''
  try {
    const value = await askTaskResultApi(props.taskId, body, lifetime.signal)
    if (!lifetime.signal.aborted) {
      const existing = turns.value.findIndex(item => item.requestId === value.requestId)
      if (existing === -1) {
        turns.value.push(value)
        turns.value.sort((left, right) => left.number - right.number)
      } else turns.value[existing] = value
      resolvePending(value)
    }
  } catch (error) {
    if (lifetime.signal.aborted) return
    if (stale(error)) invalidate()
    if (error instanceof ApiError) {
      feedback.value = error.message
      if (error.code === 400) pending.value = null
    } else if (isUnauthorized(error)) {
      feedback.value = '登录已失效，请重新登录'
    } else {
      feedback.value = '暂时无法确认解读状态，可以查询回答或重试本次请求。'
    }
  } finally { busy.value = false }
}

function formatValue(value: number, unit: string) {
  const formatted = (unit === 'ratio' ? value * 100 : value).toLocaleString('zh-CN', { maximumFractionDigits: 6 })
  return unit === 'ratio' ? `${formatted}%` : formatted
}

onMounted(() => refresh())
onBeforeUnmount(() => lifetime.abort())
</script>

<style scoped>
.result-answers { margin: 20px 0; padding: 20px; background: #f8faf7; border: 1px solid #dce6dd; border-radius: 8px; }
.result-answers h3 { margin: 0 0 14px; }
.result-answer-actions { display: flex; flex-wrap: wrap; gap: 8px; margin: 10px 0; }
.result-answer-actions :deep(.el-button) { margin-left: 0; }
label { display: block; margin-bottom: 6px; }
.saved-answer { margin-top: 16px; padding-top: 14px; border-top: 1px solid #dce6dd; }
.saved-answer summary { cursor: pointer; color: #175b47; }
.saved-answer dl { display: flex; flex-wrap: wrap; gap: 12px 24px; }
.saved-answer dd { margin: 4px 0 0; font-variant-numeric: tabular-nums; }
.partitions { display: grid; gap: 8px; margin: 12px 0; padding: 0; list-style: none; }
.partitions li { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; padding: 8px 12px; border: 1px solid #dce6dd; border-radius: 6px; }
.partitions small { color: #526458; }
</style>
