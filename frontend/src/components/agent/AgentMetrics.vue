<template>
  <div class="metrics" aria-label="任务真实统计">
    <div class="metric lead"><span>区域 NDVI 均值</span><strong>{{ number(statistics.mean) }}</strong><small>单期区域统计</small></div>
    <div class="metric"><span>最小 / 最大</span><strong>{{ number(statistics.min) }} / {{ number(statistics.max) }}</strong></div>
    <div class="metric"><span>有效像素比例</span><strong>{{ percent(statistics.validPixelRatio) }}</strong><small>不是植被覆盖率</small></div>
    <div class="metric"><span>有效 / 总像素</span><strong>{{ number(statistics.validPixelCount) }} / {{ number(statistics.totalPixelCount) }}</strong></div>
    <div class="metric"><span>无效像素</span><strong>{{ number(statistics.invalidPixelCount) }}</strong></div>
    <div class="metric"><span>区域完整覆盖率</span><strong>{{ percent(coverageRatio) }}</strong></div>
  </div>
</template>
<script setup lang="ts">
import type { AgentStatistics } from '@/types/agent'
defineProps<{ statistics: AgentStatistics; coverageRatio: number }>()
function number(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString('zh-CN', { maximumFractionDigits: 6 }) : '缺失' }
function percent(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? `${number(value * 100)}%` : '缺失' }
</script>
<style scoped>
.metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border: 1px solid #d8e1d7; margin: 20px 0; }
.metric { padding: 16px; border-bottom: 1px solid #e5e9e0; min-width: 0; display: flex; flex-direction: column; }.metric span, .metric small { font-size: 12px; color: #66776c; }
.metric strong { font-variant-numeric: tabular-nums; overflow-wrap: anywhere; font-size: 17px; font-weight: 600; }.metric.lead { background: #edf2e8; }.metric.lead strong { font-family: Georgia, serif; font-size: 30px; line-height: 1.35; }
@media (max-width: 650px) { .metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }.metric { padding: 12px; } }
</style>
