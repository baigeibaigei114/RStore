<template>
  <details class="trace">
    <summary>执行轨迹 <span>{{ steps.length }} 个步骤</span></summary>
    <p v-if="!steps.length" class="hint">暂无已保存步骤。</p>
    <ol>
      <li v-for="step in steps" :key="step.sequence">
        <div class="step-top"><strong>{{ step.sequence }} · {{ step.name }}</strong><span>{{ step.status }}</span></div>
        <p>{{ step.kind }} · {{ step.durationMs === undefined ? '进行中' : `${step.durationMs} ms` }} · {{ usage(step) }}</p>
        <p v-if="step.modelReturned" class="model">{{ step.modelReturned }}</p>
        <code v-if="step.errorCode">{{ step.errorCode }}</code>
        <details v-if="step.input || step.output"><summary>安全摘要</summary><pre>{{ JSON.stringify({ input: step.input, output: step.output }, null, 2) }}</pre></details>
      </li>
    </ol>
  </details>
</template>

<script setup lang="ts">
import type { AgentStep } from '@/types/agent'
defineProps<{ steps: AgentStep[] }>()
function usage(step: AgentStep) {
  if (step.kind !== 'MODEL') return '非模型步骤'
  const total = step.usage?.total_tokens
  return typeof total === 'number' ? `${total} tokens` : 'usage 未收到'
}
</script>

<style scoped>
.trace { border-top: 1px solid #dce3d8; padding-top: 16px; font-size: 12px; color: #526558; }.trace > summary { cursor: pointer; font-weight: 600; }.trace > summary span { font-weight: 400; margin-left: 10px; color: #788572; }
ol { list-style: none; padding: 0 0 0 10px; margin: 18px 0 0 4px; border-left: 1px solid #cbd8c6; }li { position: relative; padding: 0 0 18px 10px; }li::before { content: ''; position: absolute; left: -14px; top: 6px; width: 7px; height: 7px; border: 1px solid #8da67e; background: #edf3e8; border-radius: 50%; }
.step-top { display: flex; justify-content: space-between; gap: 8px; }.step-top span { font-size: 10px; letter-spacing: .04em; }.model, p { margin: 5px 0; }pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 11px; background: #f0f3eb; padding: 10px; }.hint { padding: 12px 0; }code { font-size: 11px; }
</style>
