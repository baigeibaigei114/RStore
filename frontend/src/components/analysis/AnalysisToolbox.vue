<template>
  <div class="analysis-tools" :class="{ stacked }">
    <template v-for="tool in visibleTools" :key="tool.id">
      <RouterLink v-if="available(tool.id) && !(tool.id === 'region' && embeddedRegion)" class="analysis-tool" :to="tool.route">
        <span class="tool-icon"><el-icon><component :is="tool.icon" /></el-icon></span>
        <span class="tool-label"><strong>{{ tool.label }}</strong><small>{{ tool.description }}</small></span>
        <el-icon class="tool-arrow"><ArrowRight /></el-icon>
      </RouterLink>
      <button v-else class="analysis-tool" type="button" :disabled="!available(tool.id)" @click="emit('region')">
        <span class="tool-icon"><el-icon><component :is="tool.icon" /></el-icon></span>
        <span class="tool-label"><strong>{{ tool.label }}</strong><small>{{ available(tool.id) ? tool.description : modelStatus }}</small></span>
        <el-icon v-if="available(tool.id)" class="tool-arrow"><ArrowRight /></el-icon>
      </button>
    </template>
    <el-button v-if="modelError" class="tool-retry" text @click="retry++">重试读取模型状态</el-button>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ArrowRight, CollectionTag, DataAnalysis, Grid, Picture } from '@element-plus/icons-vue'
import { getBuildingChangeOptionsApi } from '@/api/task'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{ group?: 'compute' | 'model'; stacked?: boolean; embeddedRegion?: boolean }>()
const emit = defineEmits<{ region: [] }>()
const auth = useAuthStore()
const tools = [
  { id: 'region', label: '区域 NDVI', description: '分析监测区植被绿度', icon: CollectionTag, route: '/tasks/create?mode=region-ndvi' },
  { id: 'water', label: 'NDWI', description: '计算水体指数', icon: Picture, route: '/tasks/create?taskType=NDWI' },
  { id: 'difference', label: '波段差值变化检测', description: '对比两期影像的指定波段', icon: DataAnalysis, route: '/tasks/create?taskType=CHANGE_DETECTION' },
  { id: 'building', label: '建筑物变化检测', description: '检测两期影像的建筑物变化', icon: Grid, route: '/tasks/create?taskType=BUILDING_CHANGE_DETECTION' },
] as const
const visibleTools = computed(() => tools.filter(tool => !props.group || (tool.id === 'building') === (props.group === 'model')))
const enabled = ref(false), loading = ref(false), modelError = ref(false), retry = ref(0)
const modelStatus = computed(() => loading.value ? '正在读取模型状态…' : modelError.value ? '模型状态暂不可用' : '模型检测暂未启用')
const available = (id: string) => id !== 'building' || enabled.value
watch([() => props.group, () => auth.token, retry], async ([group, token], _, onCleanup) => {
  enabled.value = false
  modelError.value = false
  loading.value = false
  if (group === 'compute' || !token) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  loading.value = true
  try {
    const options = await getBuildingChangeOptionsApi(request.signal)
    if (!request.signal.aborted) enabled.value = options.enabled
  } catch {
    if (!request.signal.aborted) modelError.value = true
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}, { immediate: true })
</script>

<style scoped>
.analysis-tools { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 220px), 1fr)); gap: 9px; }
.analysis-tools.stacked { grid-template-columns: minmax(0, 1fr); }
.analysis-tool { display: flex; align-items: center; gap: 12px; min-width: 0; padding: 13px 12px; border: 1px solid var(--work-border); border-radius: 6px; color: var(--work-text); background: var(--work-panel); text-align: left; text-decoration: none; font: inherit; cursor: pointer; }
.analysis-tool:not(:disabled):hover { background: var(--work-hover); border-color: #597ca8; }
.analysis-tool:disabled { cursor: not-allowed; background: var(--work-inset); }
.analysis-tool:disabled .tool-icon, .analysis-tool:disabled strong { color: var(--work-muted); }
.tool-icon { display: grid; flex: 0 0 34px; height: 34px; place-items: center; border-radius: 4px; background: var(--work-selected); color: var(--work-accent); font-size: 17px; }
.tool-label { display: grid; min-width: 0; flex: 1; gap: 4px; }
.tool-label strong { font-size: 13px; overflow-wrap: anywhere; }
.tool-label small { color: var(--work-muted); font-size: 11px; line-height: 1.5; }
.tool-arrow { color: var(--work-accent); flex: 0 0 14px; }
.tool-retry { justify-self: start; }
</style>
