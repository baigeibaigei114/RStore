<template>
  <section class="result-browser" aria-label="选择分析结果" :aria-busy="loading">
    <template v-if="taskId">
      <div v-if="results[0]" class="map-result-summary">
        <h4>{{ mapResultTitle(results[0]) }}</h4>
        <p>{{ mapResultDetail(results[0]) }}</p>
      </div>
      <div class="map-result-actions">
        <el-button link :loading="loading" @click="refresh">刷新</el-button>
        <el-button link @click="emit('all')">查看全部</el-button>
      </div>
    </template>
    <template v-else>
      <div v-if="imageId" class="map-result-actions">
        <span>当前影像的分析结果</span>
        <el-button link @click="emit('all')">查看全部</el-button>
      </div>
      <el-input v-model="keyword" clearable placeholder="搜索影像或分析" aria-label="搜索影像或分析" @keyup.enter="search">
        <template #append><el-button :icon="Search" aria-label="搜索分析结果" @click="search" /></template>
      </el-input>
      <div class="filters">
        <el-select v-model="taskType" placeholder="全部分析" aria-label="分析类型" @change="search">
          <el-option label="全部分析" value="" />
          <el-option label="植被绿度" value="NDVI" />
          <el-option label="水体指数" value="NDWI" />
          <el-option label="地表变化" value="CHANGE_DETECTION" />
          <el-option label="建筑物变化检测" value="BUILDING_CHANGE_DETECTION" />
        </el-select>
        <el-button link :loading="loading" @click="refresh">刷新</el-button>
      </div>
    </template>
    <p v-if="error" role="status" class="feedback">{{ error }} <el-button link @click="refresh">重试</el-button>
      <router-link v-if="taskId" :to="`/tasks/${taskId}`">返回分析</router-link>
    </p>
    <p v-else-if="loading && !results.length" role="status" class="empty">正在读取分析结果…</p>
    <slot />
    <div v-if="!taskId && !loading && !error && !results.length" class="empty">
      <p>还没有可查看的结果</p><span>分析完成后，可在任务详情中打开地图。</span>
      <router-link to="/tasks">查看我的任务 →</router-link>
    </div>
    <div v-if="!taskId && results.length" class="map-result-items">
      <button v-for="result in results" :key="result.id" type="button" class="result-option"
        :class="{ selected: result.id === loadedId }" :aria-pressed="result.id === loadedId" @click="emit('select', result)">
        <span class="result-mark" aria-hidden="true"><el-icon><DataAnalysis /></el-icon></span>
        <span class="result-copy"><strong>{{ mapResultTitle(result) }}</strong><small>{{ mapResultDetail(result) }}</small></span>
        <el-icon class="arrow" aria-hidden="true"><ArrowRight /></el-icon>
      </button>
    </div>
    <el-pagination v-if="!taskId && total > 20" small layout="prev, pager, next" :current-page="page" :page-size="20" :total="total" @current-change="changePage" />
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ArrowRight, DataAnalysis, Search } from '@element-plus/icons-vue'
import { listLayersApi } from '@/api/layer'
import { getTaskDetailApi, getTaskResultFileApi } from '@/api/task'
import { TOKEN_KEY } from '@/api/request'
import { mapResultDetail, mapResultTitle, mapTaskResult } from '@/utils/mapPresentation'
import type { LayerListItem } from '@/types/layer'

const props = defineProps<{ taskId?: number; imageId?: number; initialTaskType?: string; loadedId?: number }>()
const emit = defineEmits<{ select: [result: LayerListItem]; all: [] }>()
const keyword = ref(''), taskType = ref(props.initialTaskType || ''), page = ref(1), total = ref(0)
const loading = ref(false), error = ref(''), results = ref<LayerListItem[]>([])
let request = 0
async function fetchResults(autoOpen: boolean, refreshSelected = false) {
  const call = ++request, token = localStorage.getItem(TOKEN_KEY)
  loading.value = true; error.value = ''
  try {
    let records: LayerListItem[], count: number
    if (props.taskId) {
      const result = await listLayersApi({ taskId: props.taskId, pageSize: 1 })
      let exact = result.records?.find(layer => String(layer.taskId) === String(props.taskId))
      if (!exact) {
        const [task, file] = await Promise.all([getTaskDetailApi(props.taskId, true), getTaskResultFileApi(props.taskId, true)])
        exact = mapTaskResult(task, file) ?? undefined
      }
      if (!exact) throw new Error('Task map unavailable')
      records = [exact]; count = 1
    } else {
      const result = await listLayersApi({ pageNum: page.value, pageSize: 20, imageId: props.imageId, taskType: taskType.value, keyword: keyword.value.trim() })
      records = result.records || []; count = result.total || 0
    }
    if (call !== request || token !== localStorage.getItem(TOKEN_KEY)) return
    results.value = records; total.value = count
    const selected = autoOpen ? records[0] : refreshSelected ? records.find(result => result.id === props.loadedId) : undefined
    if (selected) emit('select', selected)
  } catch {
    if (call === request && token === localStorage.getItem(TOKEN_KEY)) {
      error.value = props.taskId ? '暂时无法读取这次分析的地图。' : '暂时无法读取结果。'
    }
  } finally { if (call === request) loading.value = false }
}
function search() { page.value = 1; void fetchResults(false) }
function changePage(value: number) { page.value = value; void fetchResults(false) }
function refresh() { void fetchResults(!!props.taskId, true) }
watch(() => [props.taskId, props.imageId, props.initialTaskType], () => {
  results.value = []; total.value = 0; page.value = 1
  keyword.value = ''; taskType.value = props.initialTaskType || ''
  void fetchResults(!!props.taskId || !!props.imageId)
}, { immediate: true })
onBeforeUnmount(() => { request++ })
</script>

<style scoped>
.map-result-summary { border: 1px solid var(--work-border); border-radius: 7px; padding: 10px 12px; }
.map-result-summary h4 { margin: 0; color: var(--work-text); font-size: 14px; line-height: 1.55; overflow-wrap: anywhere; }
.map-result-summary p { margin: 4px 0 0; font-size: 12px; color: var(--work-secondary); line-height: 1.5; }
.map-result-actions { display: flex; align-items: center; gap: 16px; margin: 4px 0 12px; font-size: 13px; color: var(--work-secondary); }
.map-result-actions .el-button { margin: 0; }
.filters { display: flex; align-items: center; gap: 12px; margin: 12px 0 20px; }.filters .el-select { flex: 1; }
.map-result-items { display: grid; gap: 8px; }
.result-option { display: flex; align-items: center; gap: 10px; width: 100%; padding: 10px 11px; text-align: left; background: var(--work-panel); border: 1px solid var(--work-border); border-radius: 7px; color: var(--work-text); cursor: pointer; transition: border-color .15s; }
.result-option:hover, .result-option:focus-visible { border-color: var(--work-border); }.result-option.selected { border-color: var(--work-border); }
.result-mark { display: grid; place-items: center; flex: 0 0 30px; height: 34px; background: var(--work-hover); color: var(--work-secondary); border-radius: 5px; }
.result-copy { display: grid; gap: 4px; min-width: 0; flex: 1; }.result-copy strong { font-size: 13px; font-weight: 550; line-height: 1.5; overflow-wrap: anywhere; }.result-copy small { font-size: 11px; color: var(--work-secondary); }
.arrow { color: var(--work-muted); flex-shrink: 0; }.empty { padding: 20px 4px; color: var(--work-secondary); font-size: 13px; line-height: 1.8; }.empty p { margin: 0 0 8px; color: var(--work-text); }.empty span, .empty a { display: block; }.empty a { margin-top: 12px; color: var(--work-text); }.feedback { font-size: 13px; line-height: 1.7; color: var(--work-warning); }.el-pagination { margin-top: 16px; }
</style>
