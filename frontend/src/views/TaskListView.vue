<template>
  <section class="analysis-history">
    <div class="page-title">
      <span>我的分析</span>
      <h2>分析记录</h2>
      <p>继续查看正在进行的分析，或打开已经完成的结果。</p>
    </div>

    <el-card class="table-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>全部分析</span>
          <div class="table-toolbar">
            <el-switch
              v-model="autoRefresh"
              active-text="自动更新"
              aria-label="自动更新分析状态"
              @change="handleAutoRefreshChange"
            />
            <el-button :icon="Refresh" :loading="loading" :disabled="expired" @click="refreshTasks">刷新</el-button>
            <el-button class="new-analysis" type="primary" :icon="Plus" @click="router.push('/tasks/create?mode=region-ndvi')">
              开始区域分析
            </el-button>
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
        <li v-for="task in taskList" :key="task.id" class="analysis-row">
          <div class="analysis-subject">
            <h3>{{ task.imageName || '未命名影像' }}</h3>
            <p>{{ taskTypeText(task.taskType) }}</p>
          </div>
          <div class="analysis-state">
            <el-tag :type="taskStatusType(task.status)" effect="plain">{{ taskStatusText(task.status) }}</el-tag>
            <time :datetime="task.submittedAt">{{ formatDateTime(task.submittedAt) }}</time>
          </div>
          <el-button @click="router.push(`/tasks/${task.id}`)">
            {{ task.status === 'SUCCESS' ? '查看结果' : isActiveStatus(task.status) ? '继续查看' : '查看详情' }}
          </el-button>
        </li>
      </ul>
      <el-empty v-else-if="hasLoaded && !connectionError" description="还没有分析记录，从一次区域分析开始吧" />

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
import { Plus, Refresh } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { listTasksApi } from '@/api/task'
import type { TaskListItem, TaskStatus, TaskType } from '@/types/task'
import { useAuthStore } from '@/stores/auth'
import { isUnauthorized } from '@/utils/regionAnalysis'
import { createRefreshController, isActiveStatus, taskStatusText } from '@/utils/taskPresentation'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const autoRefresh = ref(true)
const taskList = ref<TaskListItem[]>([])
const connectionError = ref(''), expired = ref(false), lastRead = ref('')
const hasLoaded = ref(false), paused = ref(false)
let alive = true, generation = 0
let confirmedPage = { pageNum: 1, pageSize: 10 }

const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
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

watch(() => auth.userInfo?.userId, () => {
  generation++
  controller.stop()
  taskList.value = []
  hasLoaded.value = false
  loading.value = false
  connectionError.value = ''; expired.value = false; lastRead.value = ''
  pagination.pageNum = 1; pagination.total = 0
  confirmedPage = { pageNum: 1, pageSize: pagination.pageSize }
  if (alive && auth.isLoggedIn) refreshTasks()
  else if (alive) { expired.value = true; connectionError.value = '登录已过期，请重新登录后继续查看。' }
})

async function readTasks(): Promise<boolean> {
  const epoch = generation
  const current = () => alive && epoch === generation
  loading.value = true
  try {
    const page = await listTasksApi({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
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
    CHANGE_DETECTION: '变化检测',
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
.analysis-history { max-width: 1160px; }
.list-feedback { margin-bottom: 16px; padding: 12px 16px; border-left: 3px solid #d7a04a; background: #fffaeb; color: #73511a; }
.list-feedback p { margin: 0; line-height: 1.7; }
.list-feedback .el-button { margin-top: 10px; }
.last-read { margin: 0 0 16px; color: #667085; font-size: 12px; line-height: 1.7; }
.card-header, .table-toolbar { flex-wrap: wrap; gap: 12px; }
.new-analysis { --el-button-bg-color: #176b52; --el-button-border-color: #176b52; --el-button-hover-bg-color: #215e4c; --el-button-hover-border-color: #215e4c; }
.analysis-list { margin: 0; padding: 0; list-style: none; }
.analysis-row { display: grid; grid-template-columns: minmax(0, 1fr) 180px auto; align-items: center; gap: 24px; padding: 20px 0; border-bottom: 1px solid #eaecf0; }
.analysis-row:first-child { padding-top: 4px; }
.analysis-row:last-child { border-bottom: 0; }
.analysis-subject { min-width: 0; }
.analysis-subject h3 { margin: 0 0 7px; font-size: 16px; line-height: 1.5; overflow-wrap: anywhere; }
.analysis-subject p { margin: 0; color: #667085; font-size: 13px; overflow-wrap: anywhere; }
.analysis-state { display: flex; flex-direction: column; align-items: flex-start; gap: 8px; }
.analysis-state time { color: #667085; font-size: 12px; }
.pagination-bar :deep(.el-pagination) { flex-wrap: wrap; gap: 8px; }
@media (max-width: 900px) {
  .analysis-row { grid-template-columns: minmax(0, 1fr) auto; gap: 14px; }
  .analysis-subject { grid-column: 1 / -1; }
  .analysis-row > .el-button { justify-self: end; }
}
</style>
