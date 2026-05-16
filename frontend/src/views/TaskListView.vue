<template>
  <section>
    <div class="page-title">
      <span>任务管理</span>
      <h2>解译任务列表</h2>
      <p>查看异步任务状态、进度、错误信息和结果对象，运行中的任务可开启自动刷新。</p>
    </div>

    <el-card class="table-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>任务列表</span>
          <div class="table-toolbar">
            <el-switch
              v-model="autoRefresh"
              active-text="自动刷新"
              inactive-text="手动刷新"
              @change="handleAutoRefreshChange"
            />
            <el-button :icon="Refresh" :loading="loading" @click="fetchTasks">刷新</el-button>
            <el-button type="primary" :icon="Plus" @click="router.push('/tasks/create')">
              创建任务
            </el-button>
          </div>
        </div>
      </template>

      <el-table v-loading="loading" :data="taskList" row-key="id" empty-text="暂无任务数据">
        <el-table-column prop="taskCode" label="任务编码" min-width="170" show-overflow-tooltip />
        <el-table-column prop="imageName" label="影像名称" min-width="180" show-overflow-tooltip />
        <el-table-column label="任务类型" width="150">
          <template #default="{ row }">
            {{ taskTypeText(row.taskType) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="taskStatusType(row.status)" effect="plain">
              {{ taskStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="180">
          <template #default="{ row }">
            <el-progress :percentage="row.progress ?? 0" :status="progressStatus(row.status)" />
          </template>
        </el-table-column>
        <el-table-column label="输出对象" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.outputObjectKey || '暂无' }}
          </template>
        </el-table-column>
        <el-table-column label="错误信息" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.errorMessage || '无' }}
          </template>
        </el-table-column>
        <el-table-column label="提交时间" min-width="180">
          <template #default="{ row }">
            {{ formatDateTime(row.submittedAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="router.push(`/tasks/${row.id}`)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-bar">
        <el-pagination
          v-model:current-page="pagination.pageNum"
          v-model:page-size="pagination.pageSize"
          :page-sizes="[10, 20, 50]"
          :total="pagination.total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="fetchTasks"
          @current-change="fetchTasks"
        />
      </div>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { listTasksApi } from '@/api/task'
import type { TaskListItem, TaskStatus, TaskType } from '@/types/task'

const router = useRouter()
const loading = ref(false)
const autoRefresh = ref(true)
const taskList = ref<TaskListItem[]>([])
const pollingTimer = ref<number>()

const pagination = reactive({
  pageNum: 1,
  pageSize: 10,
  total: 0,
})

const hasActiveTask = computed(() => taskList.value.some((item) => isActiveStatus(item.status)))

onMounted(() => {
  fetchTasks()
})

onBeforeUnmount(() => {
  stopPolling()
})

watch(hasActiveTask, () => {
  syncPolling()
})

async function fetchTasks() {
  loading.value = true
  try {
    const page = await listTasksApi({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
    })
    taskList.value = page.records
    pagination.total = page.total
    pagination.pageNum = page.pageNum
    pagination.pageSize = page.pageSize
    syncPolling()
  } finally {
    loading.value = false
  }
}

function handleAutoRefreshChange() {
  syncPolling()
}

function syncPolling() {
  if (autoRefresh.value && hasActiveTask.value) {
    startPolling()
    return
  }
  stopPolling()
}

function startPolling() {
  if (pollingTimer.value) {
    return
  }

  pollingTimer.value = window.setInterval(() => {
    fetchTasks()
  }, 3000)
}

function stopPolling() {
  if (pollingTimer.value) {
    window.clearInterval(pollingTimer.value)
    pollingTimer.value = undefined
  }
}

function isActiveStatus(status: TaskStatus) {
  return status === 'PENDING' || status === 'RUNNING' || status === 'RETRYING'
}

function taskTypeText(type: TaskType) {
  const map: Record<TaskType, string> = {
    NDVI: '植被指数',
    NDWI: '水体指数',
    CHANGE_DETECTION: '变化检测',
  }
  return map[type] || type
}

function taskStatusText(status: TaskStatus) {
  const map: Record<TaskStatus, string> = {
    PENDING: '等待中',
    RUNNING: '运行中',
    SUCCESS: '成功',
    FAILED: '失败',
    RETRYING: '重试中',
    CANCELED: '已取消',
  }
  return map[status] || status
}

function taskStatusType(status: TaskStatus) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'CANCELED') return 'danger'
  if (status === 'RUNNING' || status === 'PENDING' || status === 'RETRYING') return 'warning'
  return 'info'
}

function progressStatus(status: TaskStatus) {
  if (status === 'SUCCESS') return 'success'
  if (status === 'FAILED' || status === 'CANCELED') return 'exception'
  return undefined
}

function formatDateTime(value?: string) {
  if (!value) {
    return '未填写'
  }
  return new Date(value).toLocaleString()
}
</script>
