<template>
  <section class="result-answers" aria-labelledby="result-answers-title">
    <h3 id="result-answers-title">历史结果解读</h3>
    <div v-if="state?.historyVersions.length" class="result-answer-actions">
      <label for="result-answer-version">对话版本</label>
      <select id="result-answer-version" v-model="selectedVersion" :disabled="busy" @change="changeVersion">
        <option value="">当前结果</option>
        <option v-for="(version, index) in state.historyVersions" :key="version" :value="version">
          历史结果 {{ index + 1 }}
        </option>
      </select>
    </div>
    <p v-if="feedback" role="status">{{ feedback }}</p>
    <p v-else-if="state?.message">{{ state.message }}</p>
    <p v-if="selectedVersion">正在查看以往结果版本的对话。</p>
    <el-button v-if="state?.hasEarlier" :disabled="busy" @click="refresh(true)">加载更早的问答</el-button>
    <div aria-live="polite">
      <article v-for="entry in turns" :key="entry.requestId" class="saved-answer">
        <strong>{{ entry.question }}</strong>
        <p v-if="entry.status === 'RUNNING'">这次解读未完成。</p>
        <p v-else-if="entry.status === 'FAILED'">{{ entry.error || '本次解读未完成。' }}</p>
        <template v-else-if="entry.answer">
          <p>{{ entry.answer.statement }}</p>
          <details v-if="entry.answer.evidence.length || entry.answer.partitions?.length || entry.answer.sources?.length" :open="entry.requestId === turn?.requestId">
            <summary>查看数据依据</summary>
            <p>本次任务 · {{ entry.answer.regionVersion ? `区域版本 ${entry.answer.regionVersion}` : '整幅结果影像' }}</p>
            <dl>
              <div v-for="item in entry.answer.evidence" :key="item.key">
                <dt>{{ item.label }}</dt>
                <dd>{{ item.key === 'threshold' ? item.value : formatValue(item.value, item.unit) }}</dd>
              </div>
            </dl>
            <dl v-if="entry.answer.sources?.length">
              <div v-for="source in entry.answer.sources" :key="source.period">
                <dt>{{ source.period === 'before' ? '对比前影像' : '对比后影像' }}</dt>
                <dd>{{ formatSource(source) }}</dd>
              </div>
            </dl>
            <ul v-if="entry.answer.partitions?.length" class="partitions">
              <li v-for="part in entry.answer.partitions" :key="part.id">
                <strong>{{ sectionLabels[part.id] }}分区</strong>
                <template v-if="algorithm === 'CHANGE_DETECTION'">
                  <span>变化比例 {{ part.mean == null ? '暂无共同有效像素' : formatValue(part.mean, 'ratio') }}</span>
                  <span v-if="part.changed !== undefined">变化像素 {{ formatValue(part.changed, 'count') }}</span>
                  <span>共同有效像素 {{ formatValue(part.valid, 'count') }} / {{ formatValue(part.total, 'count') }}</span>
                </template>
                <template v-else>
                  <span>均值 {{ part.mean == null ? '暂无有效像素' : formatValue(part.mean, 'decimal') }}</span>
                  <span>有效像素 {{ formatValue(part.valid, 'count') }} / {{ formatValue(part.total, 'count') }}</span>
                </template>
                <el-button v-if="part.locatable && mapReady && entry.resultVersion === state?.currentVersion" link type="primary"
                  @click="openPartition(entry.resultVersion, part.id)">在地图高亮{{ sectionLabels[part.id] }}分区</el-button>
                <small v-else>该分区的地图范围暂不可用</small>
              </li>
            </ul>
          </details>
        </template>
      </article>
    </div>
    <p v-if="state && !turns.length && !feedback">没有已保存的问答。</p>
    <el-button v-if="!state && feedback" :disabled="busy" @click="refresh()">重新加载</el-button>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getTaskResultAnswerApi } from '@/api/ai'
import { ApiError } from '@/api/request'
import type { ResultSection, TaskResultAnswer, TaskResultAnswerState } from '@/types/ai'
import { isUnauthorized } from '@/utils/regionAnalysis'
import { sectionLabels } from '@/utils/resultEvidence'

const props = defineProps<{ taskId: string; algorithm: 'NDVI' | 'NDWI' | 'CHANGE_DETECTION'; mapReady: boolean }>()
const router = useRouter()
const state = ref<TaskResultAnswerState | null>(null)
const turns = ref<TaskResultAnswer[]>([])
const turn = computed(() => turns.value.at(-1))
const selectedVersion = ref('')
const feedback = ref('')
const busy = ref(false)
const lifetime = new AbortController()

function openPartition(resultVersion: string, section: ResultSection) {
  void router.push({ path: '/map', query: { taskId: props.taskId, resultVersion, section } })
}

function invalidate() {
  state.value = null
  turns.value = []
}

function stale(error: unknown) {
  return isUnauthorized(error) || (error instanceof ApiError && [404, 409].includes(error.code))
}

function changeVersion() {
  turns.value = []
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
        state.value.historyVersions = data.historyVersions
      }
      turns.value = [...data.turns, ...turns.value]
      return
    }
    state.value = data
    turns.value = data.turns
  } catch (error) {
    if (lifetime.signal.aborted) return
    if (stale(error)) invalidate()
    feedback.value = isUnauthorized(error) ? '登录已失效，请重新登录'
      : error instanceof ApiError ? error.message : '暂时无法读取解读状态'
  } finally { busy.value = false }
}

function formatValue(value: number, unit: string) {
  const formatted = (unit === 'ratio' ? value * 100 : value).toLocaleString('zh-CN', { maximumFractionDigits: 6 })
  return unit === 'ratio' ? `${formatted}%` : formatted
}

function formatSource(source: { name: string; imageId?: string; captureTime: string }) {
  const name = source.name || (source.imageId ? `影像 ${source.imageId}` : '影像名称未记录')
  const day = source.captureTime?.slice(0, 10)
  return day && name.includes(day) ? name : `${name} · ${day || '采集时间未记录'}`
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
