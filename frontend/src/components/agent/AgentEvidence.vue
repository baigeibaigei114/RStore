<template>
  <section class="evidence" aria-label="回答与依据">
    <div v-if="result.insufficientEvidence" class="insufficient" role="status">当前资料不足以支持完整回答。<template v-if="explanation">已保存的真实指标仍可查看。</template></div>
    <p class="narrative">{{ result.summary || result.answer }}</p>
    <AgentMetrics v-if="explanation" :statistics="explanation.statistics" :coverage-ratio="explanation.coverageRatio" />
    <div v-for="(claim, index) in result.claims" :key="index" class="claim">
      <p>{{ claim.text }}</p>
      <div v-if="explanation && claim.metricRefs.length" class="metric-refs">
        <span v-for="reference in claim.metricRefs" :key="reference">{{ metricLabel(reference) }}：{{ metricValue(reference) }}</span>
      </div>
      <button v-for="citation in claim.citationIds" :key="citation" class="reference-link" type="button" @click="activeCitation = citation">
        [{{ citation }}] 查看依据
      </button>
    </div>
    <p v-if="explanation" class="provenance">指标来自任务 {{ explanation.taskId }} · 区域版本 {{ explanation.regionVersion }} · NDVI / 区域统计 v2</p>
    <ul v-if="explanation?.platformLimitations.length" class="limitations"><li v-for="item in explanation.platformLimitations" :key="item">{{ item }}</li></ul>
    <div class="sources-heading"><strong>参考依据</strong><span>{{ result.citations.length }} 个片段 · 知识版本 {{ result.knowledgeIndexId.slice(0, 12) }}</span></div>
    <p v-if="!result.citations.length" class="muted">本次没有可展示的检索片段。</p>
    <details v-for="citation in result.citations" :key="citation.citationId" :open="activeCitation === citation.citationId" class="source">
      <summary>[{{ citation.citationId }}] {{ citation.title }} <span>{{ citation.sourceKind === 'PLATFORM' ? '平台约定' : '外部资料' }}</span></summary>
      <p class="source-section">{{ citation.heading }}</p>
      <blockquote>{{ citation.excerpt }}</blockquote>
      <a v-if="safeSourceUrl(citation.sourceUrl)" :href="safeSourceUrl(citation.sourceUrl)!" target="_blank" rel="noopener noreferrer">查看原始来源 ↗</a>
      <dl class="source-version"><dt>文档摘要</dt><dd>{{ citation.documentSha256 }}</dd><dt>片段标识</dt><dd>{{ citation.chunkId }}</dd></dl>
    </details>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AgentAnswer, AgentExplanation } from '@/types/agent'
import { safeSourceUrl } from '@/utils/agentSession'
import AgentMetrics from './AgentMetrics.vue'

const props = defineProps<{ result: AgentAnswer }>()
const activeCitation = ref<string | null>(null)
const explanation = computed(() => 'statistics' in props.result ? props.result as AgentExplanation : null)
watch(() => props.result, () => { activeCitation.value = null })
const labels: Record<string, string> = { 'statistics.mean': 'NDVI均值', 'statistics.min': '最小值', 'statistics.max': '最大值',
  'statistics.totalPixelCount': '总像素', 'statistics.validPixelCount': '有效像素', 'statistics.invalidPixelCount': '无效像素',
  'statistics.validPixelRatio': '有效比例', coverageRatio: '覆盖率' }
function metricLabel(reference: string) { return labels[reference] || reference }
function number(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString('zh-CN', { maximumFractionDigits: 6 }) : '缺失' }
function percent(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? `${number(value * 100)}%` : '缺失' }
function metricValue(reference: string) {
  if (!explanation.value) return '缺失'
  const key = reference.split('.')[1] as keyof AgentExplanation['statistics']
  const value = reference === 'coverageRatio' ? explanation.value.coverageRatio : explanation.value.statistics[key]
  return reference.includes('Ratio') ? percent(value) : number(value)
}
</script>

<style scoped>
.evidence { line-height: 1.8; color: #23382e; }
.narrative { font-size: 16px; margin: 0 0 20px; white-space: pre-wrap; }
.insufficient { padding: 12px 16px; background: #f9efd6; border-left: 3px solid #ae7d24; margin-bottom: 18px; }
.claim { border-left: 2px solid #9caf8e; padding-left: 14px; margin: 16px 0; }.claim p { margin: 0; }
.reference-link { border: 0; padding: 3px 8px 3px 0; background: transparent; color: #2f674b; cursor: pointer; font-size: 12px; text-decoration: underline; text-underline-offset: 3px; }
.metric-refs { display: flex; gap: 12px; flex-wrap: wrap; font-size: 12px; font-variant-numeric: tabular-nums; }
.provenance, .limitations { font-size: 12px; color: #6c766e; }.limitations { padding-left: 18px; }
.sources-heading { display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; margin: 24px 0 12px; font-size: 13px; }.sources-heading span, .muted { color: #6c766e; font-size: 12px; }
.source { border-top: 1px solid #dce3d8; padding: 12px 0; }.source summary { cursor: pointer; font-size: 14px; }.source summary span { font-size: 11px; margin-left: 8px; color: #788572; }
.source-section { font-size: 12px; margin: 12px 0 6px; }blockquote { margin: 0; padding: 12px 16px; border-left: 3px solid #a9bb99; background: #f0f3eb; white-space: pre-wrap; font-size: 13px; }
.source a { color: #2f674b; font-size: 12px; }.source-version { font-size: 10px; color: #748174; margin-top: 12px; }.source-version dd { overflow-wrap: anywhere; margin-left: 0; }
</style>
