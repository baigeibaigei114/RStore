<template>
  <section class="analysis-history">
    <div class="page-title">
      <h2>任务</h2>
    </div>
    <el-tabs :model-value="tab" @tab-change="changeTab">
      <el-tab-pane label="普通计算" name="compute" />
      <el-tab-pane label="模型检测" name="model" />
    </el-tabs>
    <div class="launch-actions" aria-label="分析工具"><AnalysisToolbox :group="tab" /></div>

    <el-card class="table-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>{{ tab === 'model' ? '检测记录' : '计算记录' }}</span>
          <div class="table-toolbar">
            <el-switch
              v-model="autoRefresh"
              active-text="自动更新"
              aria-label="自动更新分析状态"
              @change="handleAutoRefreshChange"
            />
            <el-button :icon="Refresh" :loading="loading" :disabled="expired" @click="refreshTasks">刷新</el-button>
          </div>
        </div>
      </template>

      <div v-if="connectionError" class="list-feedback" role="status">
        <p>{{ connectionError }}</p>
        <el-button v-if="expired" @click="relogin">重新登录并返回分析记录</el-button>
      </div>
      <p v-if="lastRead" class="last-read">最近成功读取：{{ lastRead }}<span v-if="paused"> · 自动更新已暂停，可点击刷新继续读取</span></p>
      <el-skeleton v-if="loading && !hasLoaded" :rows="5" animated />
      <ul v-else-if="taskList.length" class="analysis-list" aria-label="分析记录">
        <li v-for="task in taskList" :key="task.id" class="analysis-row" :class="{ 'analysis-row--selected': String(task.id) === route.query.selected }">
          <div class="analysis-subject">
            <h3>{{ task.imageName || '未命名影像' }}</h3>
            <p>{{ taskTypeText(task.taskType) }}</p>
          </div>
          <div class="analysis-state">
            <el-tag :type="taskStatusType(task.status)" effect="plain">{{ taskStatusText(task.status) }}</el-tag>
            <time :datetime="task.submittedAt">{{ formatDateTime(task.submittedAt) }}</time>
          </div>
          <el-button @click="openTask(task.id)">
            {{ task.status === 'SUCCESS' ? '查看结果' : isActiveStatus(task.status) ? '继续查看' : '查看详情' }}
          </el-button>
        </li>
      </ul>
      <el-empty v-else-if="hasLoaded && !connectionError" description="暂无记录" />

      <div v-if="hasLoaded && pagination.total" class="pagination-bar">
        <el-pagination
          :current-page="pagination.pageNum"
          :page-size="pagination.pageSize"
          :disabled="loading || expired"
          :page-sizes="[10, 20, 50]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next"
          @size-change="changePageSize"
          @current-change="changePage"
        />
      </div>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { listTasksApi } from '@/api/task'
import AnalysisToolbox from '@/components/analysis/AnalysisToolbox.vue'
import type { TaskListItem, TaskStatus, TaskType } from '@/types/task'
import { useAuthStore } from '@/stores/auth'
import { isUnauthorized, positiveId } from '@/utils/regionAnalysis'
import { createRefreshController, isActiveStatus, taskStatusText } from '@/utils/taskPresentation'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const tab = computed(() => route.query.tab === 'model' ? 'model' : 'compute')
const returnPage = () => positiveId(route.query.page) ?? 1
const returnPageSize = () => [10, 20, 50].find(size => String(size) === route.query.size) ?? 10
const loading = ref(false)
const autoRefresh = ref(true)
const taskList = ref<TaskListItem[]>([])
const connectionError = ref(''), expired = ref(false), lastRead = ref('')
const hasLoaded = ref(false), paused = ref(false)
let alive = true, generation = 0
let confirmedPage = { pageNum: returnPage(), pageSize: returnPageSize() }

const pagination = reactive({
  pageNum: returnPage(),
  pageSize: returnPageSize(),
  total: 0,
})

const hasActiveTask = computed(() => taskList.value.some((item) => isActiveStatus(item.status)))

const controller = createRefreshController(readTasks, setTimeout, clearTimeout, () => {
  paused.value = autoRefresh.value && hasActiveTask.value && !expired.value
})

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  refreshTasks()
})
onBeforeUnmount(() => {
  alive = false
  generation++
  controller.stop()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

watch([() => auth.userInfo?.userId, tab], () => {
  generation++
  controller.stop()
  taskList.value = []
  hasLoaded.value = false
  loading.value = false
  connectionError.value = ''; expired.value = false; lastRead.value = ''
  pagination.pageNum = returnPage(); pagination.total = 0
  confirmedPage = { pageNum: pagination.pageNum, pageSize: pagination.pageSize }
  if (alive && auth.isLoggedIn) refreshTasks()
  else if (alive) { expired.value = true; connectionError.value = '登录已过期，请重新登录后继续查看。' }
})

function openTask(id: number) {
  void router.push({ path: `/tasks/${id}`, query: {
    ...(tab.value === 'model' ? { tab: 'model' } : {}),
    ...(pagination.pageNum > 1 ? { page: pagination.pageNum } : {}),
    ...(pagination.pageSize !== 10 ? { size: pagination.pageSize } : {}),
  } })
}

function changeTab(name: string | number) {
  void router.replace({ path: '/tasks', query: name === 'model' ? { tab: 'model' } : {} })
}

async function readTasks(): Promise<boolean> {
  const epoch = generation
  const current = () => alive && epoch === generation
  loading.value = true
  try {
    const page = await listTasksApi({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      group: tab.value === 'model' ? 'MODEL' : 'COMPUTE',
    }, true)
    if (!current()) return false
    taskList.value = page.records
    pagination.total = page.total
    pagination.pageNum = page.pageNum
    pagination.pageSize = page.pageSize
    confirmedPage = { pageNum: page.pageNum, pageSize: page.pageSize }
    hasLoaded.value = true
    connectionError.value = ''; expired.value = false
    lastRead.value = new Date().toLocaleTimeString('zh-CN')
    return autoRefresh.value && hasActiveTask.value
  } catch (error) {
    if (!current()) return false
    // A failed page change must not relabel the previous page's records.
    pagination.pageNum = confirmedPage.pageNum
    pagination.pageSize = confirmedPage.pageSize
    expired.value = isUnauthorized(error)
    connectionError.value = expired.value
      ? `登录已过期。${hasLoaded.value ? '已保留上次读取的分析记录；' : ''}请重新登录后继续查看。`
      : hasLoaded.value ? '暂时无法更新，状态可能已变化。已保留上次读取的分析记录，可点击刷新重试。'
        : '暂时无法读取分析记录，请检查连接后点击刷新重试。'
    if (expired.value || !autoRefresh.value) return false
    throw error
  } finally {
    if (current()) loading.value = false
  }
}

function refreshTasks() {
  generation++
  controller.stop()
  paused.value = false
  if (!document.hidden && !expired.value) controller.start()
}

function handleAutoRefreshChange() {
  if (autoRefresh.value) refreshTasks()
  else { controller.stop(); paused.value = false }
}

function onVisibilityChange() {
  if (document.hidden) controller.stop()
  else if (autoRefresh.value && !expired.value) refreshTasks()
}

function changePage(page: number) {
  if (page === pagination.pageNum) return
  pagination.pageNum = page
  refreshTasks()
}

function changePageSize(size: number) {
  if (size === pagination.pageSize) return
  pagination.pageSize = size
  pagination.pageNum = 1
  refreshTasks()
}

function relogin() {
  auth.logout()
  void router.push({ path: '/login', query: { redirect: route.fullPath } })
}

function taskTypeText(type: TaskType) {
  const map: Record<TaskType, string> = {
    NDVI: '植被指数',
    NDWI: '水体指数',
    CHANGE_DETECTION: '波段差值变化检测',
    BUILDING_CHANGE_DETECTION: '建筑物变化检测',
  }
  return map[type] || '影像分析'
}

function taskStatusType(status: TaskStatus) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'CANCELED') return 'danger'
  if (status === 'RUNNING' || status === 'PENDING' || status === 'RETRYING') return 'warning'
  return 'info'
}

function formatDateTime(value?: string) {
  if (!value) {
    return '未记录时间'
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '未记录时间' : date.toLocaleString('zh-CN')
}
</script>

<style scoped>
.list-feedback { margin-bottom: 16px; padding: 12px 16px; border-left: 3px solid var(--work-accent); background: #362a24; color: var(--work-warning); }
.list-feedback p { margin: 0; line-height: 1.7; }
.list-feedback .el-button { margin-top: 10px; }
.last-read { margin: 0 0 16px; color: var(--work-secondary); font-size: 12px; line-height: 1.7; }
.card-header, .table-toolbar { flex-wrap: wrap; gap: 12px; }
.launch-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-block: 4px 20px; }
.launch-actions .el-button { margin-left: 0; }
.analysis-list { margin: 0; padding: 0; list-style: none; }
.analysis-row { display: grid; grid-template-columns: minmax(0, 1fr) 180px auto; align-items: center; gap: 24px; padding: 20px 0; border-bottom: 1px solid var(--work-border); }
.analysis-row:first-child { padding-top: 4px; }
.analysis-row:last-child { border-bottom: 0; }
.analysis-subject { min-width: 0; }
.analysis-subject h3 { margin: 0 0 7px; font-size: 16px; line-height: 1.5; overflow-wrap: anywhere; }
.analysis-subject p { margin: 0; color: var(--work-secondary); font-size: 13px; overflow-wrap: anywhere; }
.analysis-state { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.analysis-state time { color: var(--work-secondary); font-size: 12px; }
.pagination-bar :deep(.el-pagination) { flex-wrap: wrap; gap: 8px; }
@media (max-width: 900px) {
  .analysis-row { grid-template-columns: minmax(0, 1fr) auto; gap: 14px; }
  .analysis-subject { grid-column: 1 / -1; }
  .analysis-row > .el-button { justify-self: end; }
}
.analysis-row--selected { border-left: 2px solid var(--work-accent); background: var(--work-selected); }
.launch-actions :deep(.analysis-tools) { width: 100%; }
@media (max-width: 560px) {
  .launch-actions :deep(.analysis-tools) { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .launch-actions :deep(.tool-icon), .launch-actions :deep(.tool-arrow) { display: none; }
}
</style>
