<template>
  <section class="result-answers" aria-labelledby="result-answers-title">
    <h3 id="result-answers-title">问这次结果</h3>
    <p v-if="feedback" role="status">{{ feedback }}</p>
    <p v-else-if="state?.message">{{ state.message }}</p>
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
        <el-button v-if="pending || turn?.status === 'RUNNING'" :disabled="busy" @click="refresh">查询回答</el-button>
      </div>
    </form>
    <el-button v-if="!state && feedback" :disabled="busy" @click="refresh">重新加载</el-button>

    <article v-if="turn" class="saved-answer" aria-live="polite">
      <strong>{{ turn.question }}</strong>
      <p v-if="turn.status === 'RUNNING'">正在解读，稍后可查询回答。</p>
      <p v-else-if="turn.status === 'FAILED'">{{ turn.error || '本次解读未完成，可以重新提问。' }}</p>
      <template v-else-if="turn.answer">
        <p>{{ turn.answer.statement }}</p>
        <details v-if="turn.answer.evidence.length" open>
          <summary>查看数据依据</summary>
          <p>本次任务 · 区域版本 {{ turn.answer.regionVersion }}</p>
          <dl>
            <div v-for="item in turn.answer.evidence" :key="item.key">
              <dt>{{ item.label }}</dt>
              <dd>{{ formatValue(item.value, item.unit) }}</dd>
            </div>
          </dl>
        </details>
      </template>
    </article>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { askTaskResultApi, getTaskResultAnswerApi } from '@/api/ai'
import { ApiError } from '@/api/request'
import type { TaskResultAnswer, TaskResultAnswerState, TaskResultQuestion } from '@/types/ai'
import { isUnauthorized } from '@/utils/regionAnalysis'

const props = defineProps<{ taskId: string }>()
const examples = ['本次区域 NDVI 整体如何？', '有效像素比例是多少？', 'NDVI 最小值和最大值是多少？']
const question = ref('')
const state = ref<TaskResultAnswerState | null>(null)
const turn = ref<TaskResultAnswer | null>(null)
const pending = ref<TaskResultQuestion | null>(null)
const feedback = ref('')
const busy = ref(false)
const lifetime = new AbortController()

function applyTurn(value: TaskResultAnswer | null) {
  turn.value = value
  if (value && value.requestId === pending.value?.requestId && value.status !== 'RUNNING') {
    pending.value = null
    if (value.status === 'COMPLETE') question.value = ''
  }
}

function invalidate() {
  state.value = null
  turn.value = null
  pending.value = null
}

function stale(error: unknown) {
  return isUnauthorized(error) || (error instanceof ApiError && [404, 409].includes(error.code))
}

async function refresh() {
  if (busy.value) return
  busy.value = true
  feedback.value = ''
  try {
    const data = await getTaskResultAnswerApi(props.taskId, lifetime.signal)
    if (lifetime.signal.aborted) return
    if (pending.value && data.resultVersion !== pending.value.resultVersion) pending.value = null
    state.value = data
    applyTurn(data.turn ?? null)
  } catch (error) {
    if (lifetime.signal.aborted) return
    if (stale(error)) invalidate()
    feedback.value = isUnauthorized(error) ? '登录已失效，请重新登录'
      : error instanceof ApiError ? error.message : '暂时无法读取解读状态'
  } finally { busy.value = false }
}

async function submit() {
  if (busy.value || !state.value?.canAsk || turn.value?.status === 'RUNNING' ||
    (!pending.value && !question.value.trim())) return
  const body = pending.value ?? { requestId: crypto.randomUUID(), resultVersion: state.value.resultVersion, question: question.value.trim() }
  pending.value = body
  busy.value = true
  feedback.value = ''
  try {
    const value = await askTaskResultApi(props.taskId, body, lifetime.signal)
    if (!lifetime.signal.aborted) applyTurn(value)
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

onMounted(refresh)
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
</style>
