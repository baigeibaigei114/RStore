<template>
  <section class="result-browser" aria-label="选择分析结果">
    <div class="intro"><span>{{ taskId ? '这次分析的结果' : imageId ? '当前影像的分析结果' : '选择一份结果，直接在地图上查看。' }}</span><el-button v-if="taskId || imageId" link @click="emit('all')">查看全部</el-button></div>
    <el-button v-if="taskId" link :loading="loading" @click="fetchResults(true)">刷新地图结果</el-button>
    <el-input v-else v-model="keyword" clearable placeholder="搜索影像或分析" aria-label="搜索影像或分析" @keyup.enter="search">
      <template #append><el-button :icon="Search" aria-label="搜索分析结果" @click="search" /></template>
    </el-input>
    <div v-if="!taskId" class="filters">
      <el-select v-model="taskType" placeholder="全部分析" aria-label="分析类型" @change="search">
        <el-option label="全部分析" value="" />
        <el-option label="植被绿度" value="NDVI" />
        <el-option label="水体指数" value="NDWI" />
        <el-option label="地表变化" value="CHANGE_DETECTION" />
      </el-select>
      <el-button link :loading="loading" @click="fetchResults(false)">刷新</el-button>
    </div>
    <p v-if="error" role="status" class="feedback">{{ error }} <el-button link @click="fetchResults(!!taskId)">重试</el-button> <router-link v-if="taskId" :to="`/tasks/${taskId}`">返回分析</router-link></p>
    <p v-else-if="loading" role="status" class="empty">正在读取分析结果…</p>
    <div v-else-if="!results.length" class="empty">
      <p>还没有可查看的结果</p><span>分析完成后，可在任务详情中打开地图。</span>
      <router-link to="/tasks">查看我的任务 →</router-link>
    </div>
    <div v-else class="result-list">
      <button v-for="result in results" :key="result.id" type="button" class="result-option"
        :class="{ selected: result.id === loadedId }" :aria-pressed="result.id === loadedId" @click="emit('select', result)">
        <span class="result-mark" aria-hidden="true"><el-icon><DataAnalysis /></el-icon></span>
        <span class="result-copy"><strong>{{ mapResultTitle(result) }}</strong><small>{{ mapResultDetail(result) }}</small></span>
        <span v-if="result.id === loadedId" class="viewing">查看中</span>
        <el-icon v-else class="arrow" aria-hidden="true"><ArrowRight /></el-icon>
      </button>
    </div>
    <el-pagination v-if="total > 20" small layout="prev, pager, next" :current-page="page" :page-size="20" :total="total" @current-change="changePage" />
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
async function fetchResults(autoOpen: boolean) {
  const call = ++request, token = localStorage.getItem(TOKEN_KEY)
  loading.value = true; error.value = ''
  try {
    let records: LayerListItem[], count: number
    if (props.taskId) {
      const [task, file] = await Promise.all([getTaskDetailApi(props.taskId, true), getTaskResultFileApi(props.taskId, true)])
      const exact = mapTaskResult(task, file)
      if (!exact) throw new Error('Task map unavailable')
      records = [exact]; count = 1
    } else {
      const result = await listLayersApi({ pageNum: page.value, pageSize: 20, imageId: props.imageId, taskType: taskType.value, keyword: keyword.value.trim() })
      records = result.records || []; count = result.total || 0
    }
    if (call !== request || token !== localStorage.getItem(TOKEN_KEY)) return
    results.value = records; total.value = count
    if (autoOpen && results.value[0]) emit('select', results.value[0])
  } catch {
    if (call === request) { error.value = props.taskId ? '这次分析的地图暂不可用，请返回任务查看状态或稍后重试。' : '暂时无法读取结果，请稍后重试。' }
  } finally { if (call === request) loading.value = false }
}
function search() { page.value = 1; void fetchResults(false) }
function changePage(value: number) { page.value = value; void fetchResults(false) }
watch(() => [props.taskId, props.imageId, props.initialTaskType], () => {
  page.value = 1; taskType.value = props.initialTaskType || ''; void fetchResults(!!props.taskId || !!props.imageId)
}, { immediate: true })
onBeforeUnmount(() => { request++ })
</script>

<style scoped>
.intro { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin: 0 0 18px; color: #687c71; font-size: 13px; line-height: 1.7; }
.filters { display: flex; align-items: center; gap: 12px; margin: 12px 0 20px; }.filters .el-select { flex: 1; }
.result-list { display: grid; gap: 10px; }.result-option { display: flex; align-items: center; gap: 10px; width: 100%; padding: 14px 12px; text-align: left; background: #fff; border: 1px solid #e1e8e3; border-radius: 9px; color: #294238; cursor: pointer; transition: border-color .15s, background .15s; }
.result-option:hover, .result-option:focus-visible { border-color: #52846c; background: #f6faf7; }.result-option.selected { border-color: #36775a; background: #edf5ef; }
.result-mark { display: grid; place-items: center; flex: 0 0 30px; height: 34px; background: #edf3ed; color: #3d7655; border-radius: 5px; }
.result-copy { display: grid; gap: 7px; min-width: 0; flex: 1; }.result-copy strong { font-size: 13px; font-weight: 550; line-height: 1.6; overflow-wrap: anywhere; }.result-copy small { font-size: 11px; color: #7a897e; }
.viewing { font-size: 11px; color: #36775a; flex-shrink: 0; }.arrow { color: #95a69c; }.empty { padding: 28px 4px; color: #75867c; font-size: 13px; line-height: 1.8; }.empty p { margin: 0 0 8px; color: #3a5447; }.empty span, .empty a { display: block; }.empty a { margin-top: 20px; color: #27654a; }.feedback { font-size: 13px; line-height: 1.8; color: #9a632c; }.el-pagination { margin-top: 20px; }
</style>
