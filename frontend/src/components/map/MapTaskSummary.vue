<template>
  <section v-if="taskId" class="map-task-summary" aria-label="当前任务统计">
    <div class="summary-heading"><h3>当前任务</h3><el-button link :disabled="loading" @click="revision++">刷新</el-button></div>
    <p v-if="loading" role="status">正在读取任务状态…</p>
    <p v-else-if="unavailable" role="status">任务详情暂不可读。</p>
    <template v-else-if="task">
      <p class="summary-state">{{ taskStatusText(task.status) }}<span v-if="publication"> · {{ publication }}</span></p>
      <dl v-if="metrics.length" class="summary-metrics">
        <div v-for="metric in metrics" :key="metric.label"><dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd></div>
      </dl>
    </template>
    <RouterLink :to="{ path: `/tasks/${taskId}`, query: { from: 'map', ...(model ? { tab: 'model' } : {}) } }">查看任务详情与下载 <span aria-hidden="true">↗</span></RouterLink>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getTaskDetailApi, getTaskResultFileApi } from '@/api/task'
import { readBuildingChange, readChangeSummary, readStatistics, taskStatusText } from '@/utils/taskPresentation'
import type { TaskDetail, TaskResultFile } from '@/types/task'

const props = defineProps<{ taskId?: number; model?: boolean }>()
const task = ref<TaskDetail | null>(null), file = ref<TaskResultFile | null>(null)
const revision = ref(0), loading = ref(false), unavailable = ref(false)
watch([() => props.taskId, revision], async ([id], _, onCleanup) => {
  task.value = null
  file.value = null
  unavailable.value = false
  loading.value = Boolean(id)
  if (!id) return
  let active = true
  onCleanup(() => { active = false })
  try {
    const detail = await getTaskDetailApi(id, true)
    if (!active || String(detail.id) !== String(id)) return
    task.value = detail
    if (detail.status === 'SUCCESS') {
      const result = await getTaskResultFileApi(id, true)
      if (active && String(result.taskId) === String(id)) file.value = result
    }
  } catch {
    if (active) unavailable.value = !task.value
  } finally {
    if (active) loading.value = false
  }
}, { immediate: true })

const publication = computed(() => {
  if (file.value?.status === 'PUBLISHED') return '图层已发布'
  if (['FAILED', 'PUBLISH_FAILED'].includes(file.value?.status || '')) return '图层发布失败'
  return task.value?.status === 'SUCCESS' ? '图层尚未发布' : ''
})
const percent = (value: number) => `${(value * 100).toFixed(2)}%`
const metrics = computed(() => {
  const building = readBuildingChange(task.value, file.value)
  if (building) return [
    { label: '预测变化面积', value: `${building.area.toLocaleString('zh-CN', { maximumFractionDigits: 2 })} m²` },
    { label: '共同有效覆盖', value: percent(building.validRatio) },
  ]
  const difference = readChangeSummary(task.value, file.value)
  if (difference) return [
    { label: '变化比例', value: percent(difference.ratio) },
    { label: '共同有效覆盖', value: percent(difference.valid / difference.total) },
  ]
  const index = readStatistics(task.value, file.value)
  return index && ['NDVI', 'NDWI'].includes(task.value?.taskType || '') ? [
    { label: `${task.value?.taskType} 均值`, value: index.mean.toFixed(4) },
    { label: '有效覆盖', value: percent(index.validPixelRatio) },
  ] : []
})
</script>

<style scoped>
.map-task-summary { display: grid; gap: 10px; margin-bottom: 17px; padding: 12px; background: var(--work-inset); border: 1px solid var(--work-border); border-radius: 6px; }
.summary-heading { display: flex; align-items: center; justify-content: space-between; }
.summary-heading h3 { margin: 0; font-size: 13px; }
.map-task-summary p, .map-task-summary a { margin: 0; font-size: 12px; }
.map-task-summary p { color: var(--work-secondary); }
.summary-metrics { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; margin: 0; }
.summary-metrics > div { min-width: 0; border-left: 2px solid var(--work-accent); padding-left: 8px; }
.summary-metrics dt { color: var(--work-muted); font-size: 11px; }
.summary-metrics dd { margin: 4px 0 0; overflow-wrap: anywhere; font: 600 13px var(--work-mono); }
</style>
