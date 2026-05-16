<template>
  <section>
    <div class="page-title">
      <span>任务详情</span>
      <h2>{{ task?.taskName || task?.taskCode || `任务 ID：${taskId}` }}</h2>
      <p>查看任务状态、进度、输入输出对象、错误信息和 Worker 回调日志。</p>
    </div>

    <div class="detail-actions">
      <el-button @click="router.push('/tasks')">返回列表</el-button>
      <el-button :icon="Refresh" :loading="loading" @click="refreshAll">刷新</el-button>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :lg="16">
        <el-card class="detail-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>任务概要</span>
              <el-tag v-if="task" :type="taskStatusType(task.status)" effect="plain">
                {{ taskStatusText(task.status) }}
              </el-tag>
            </div>
          </template>

          <el-skeleton v-if="loading && !task" :rows="8" animated />

          <template v-else-if="task">
            <el-progress
              :percentage="task.progress ?? 0"
              :status="progressStatus(task.status)"
              class="task-progress"
            />

            <el-descriptions :column="2" border>
              <el-descriptions-item label="任务编码">{{ task.taskCode }}</el-descriptions-item>
              <el-descriptions-item label="任务类型">{{ taskTypeText(task.taskType) }}</el-descriptions-item>
              <el-descriptions-item label="影像名称">{{ task.imageName }}</el-descriptions-item>
              <el-descriptions-item label="影像 ID">{{ task.imageId }}</el-descriptions-item>
              <el-descriptions-item label="重试次数">
                {{ task.retryCount ?? 0 }} / {{ task.maxRetryCount ?? 0 }}
              </el-descriptions-item>
              <el-descriptions-item label="归属用户">{{ task.ownerId }}</el-descriptions-item>
              <el-descriptions-item label="提交时间">{{ formatDateTime(task.submittedAt) }}</el-descriptions-item>
              <el-descriptions-item label="开始时间">{{ formatDateTime(task.startedAt) }}</el-descriptions-item>
              <el-descriptions-item label="完成时间">{{ formatDateTime(task.finishedAt) }}</el-descriptions-item>
              <el-descriptions-item label="更新时间">{{ formatDateTime(task.updatedAt) }}</el-descriptions-item>
              <el-descriptions-item label="错误信息" :span="2">
                <span :class="{ 'error-text-inline': Boolean(task.errorMessage) }">
                  {{ task.errorMessage || '无' }}
                </span>
              </el-descriptions-item>
            </el-descriptions>
          </template>

          <el-empty v-else description="暂无任务详情" />
        </el-card>

        <el-card class="detail-card" shadow="never">
          <template #header>任务日志</template>

          <el-empty v-if="!logLoading && logs.length === 0" description="暂无日志" />

          <el-timeline v-else v-loading="logLoading" class="task-log-timeline">
            <el-timeline-item
              v-for="log in logs"
              :key="log.id"
              :timestamp="formatDateTime(log.createdAt)"
              :type="logLevelType(log.logLevel)"
              placement="top"
            >
              <div class="task-log-item">
                <div class="task-log-title">
                  <strong>{{ log.logLevel }}</strong>
                  <span>{{ log.message }}</span>
                </div>
                <pre v-if="log.detail" class="code-block task-log-detail">{{ log.detail }}</pre>
              </div>
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="8">
        <el-card class="detail-card" shadow="never">
          <template #header>对象路径</template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="输入存储桶">{{ task?.inputBucket || '未知' }}</el-descriptions-item>
            <el-descriptions-item label="输入对象">{{ task?.inputObjectKey || '未知' }}</el-descriptions-item>
            <el-descriptions-item label="输出存储桶">{{ task?.outputBucket || '暂无' }}</el-descriptions-item>
            <el-descriptions-item label="输出对象">{{ task?.outputObjectKey || '暂无' }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card class="detail-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>轮询状态</span>
              <el-tag :type="polling ? 'success' : 'info'" effect="plain">
                {{ polling ? '自动刷新中' : '已停止' }}
              </el-tag>
            </div>
          </template>
          <p class="muted-text">
            任务处于等待、运行或重试状态时，页面每 3 秒自动刷新任务详情和日志。
          </p>
        </el-card>

        <el-card class="detail-card" shadow="never">
          <template #header>任务参数</template>
          <pre class="code-block">{{ formattedParams }}</pre>
        </el-card>
      </el-col>
    </el-row>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { getTaskDetailApi, listTaskLogsApi } from '@/api/task'
import type { TaskDetail, TaskLog, TaskStatus, TaskType } from '@/types/task'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const logLoading = ref(false)
const polling = ref(false)
const task = ref<TaskDetail | null>(null)
const logs = ref<TaskLog[]>([])
const pollingTimer = ref<number>()

const taskId = computed(() => String(route.params.id))

const formattedParams = computed(() => {
  if (!task.value?.params) {
    return '暂无任务参数'
  }

  try {
    return JSON.stringify(JSON.parse(task.value.params), null, 2)
  } catch {
    return task.value.params
  }
})

onMounted(() => {
  refreshAll()
})

onBeforeUnmount(() => {
  stopPolling()
})

watch(
  () => task.value?.status,
  () => {
    syncPolling()
  },
)

async function refreshAll() {
  await Promise.all([fetchTask(), fetchLogs()])
}

async function fetchTask() {
  loading.value = true
  try {
    task.value = await getTaskDetailApi(taskId.value)
  } finally {
    loading.value = false
  }
}

async function fetchLogs() {
  logLoading.value = true
  try {
    logs.value = await listTaskLogsApi(taskId.value)
  } finally {
    logLoading.value = false
  }
}

function syncPolling() {
  if (task.value && isActiveStatus(task.value.status)) {
    startPolling()
    return
  }
  stopPolling()
}

function startPolling() {
  polling.value = true

  if (pollingTimer.value) {
    return
  }

  pollingTimer.value = window.setInterval(() => {
    refreshAll()
  }, 3000)
}

function stopPolling() {
  polling.value = false

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

function logLevelType(level: string) {
  const normalized = level?.toUpperCase()
  if (normalized === 'ERROR') return 'danger'
  if (normalized === 'WARN' || normalized === 'WARNING') return 'warning'
  if (normalized === 'INFO') return 'primary'
  return 'info'
}

function formatDateTime(value?: string) {
  if (!value) {
    return '未填写'
  }
  return new Date(value).toLocaleString()
}
</script>
