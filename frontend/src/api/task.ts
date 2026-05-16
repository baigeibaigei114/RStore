import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type {
  TaskDetail,
  TaskListItem,
  TaskLog,
  TaskPageParams,
  TaskSubmitParams,
  TaskSubmitResult,
} from '@/types/task'

export function createTaskApi(params: TaskSubmitParams) {
  return request.post<unknown, TaskSubmitResult>('/tasks', params)
}

export function listTasksApi(params: TaskPageParams) {
  return request.get<unknown, PageResult<TaskListItem>>('/tasks', { params })
}

export function getTaskDetailApi(taskId: number | string) {
  return request.get<unknown, TaskDetail>(`/tasks/${taskId}`)
}

export function listTaskLogsApi(taskId: number | string) {
  return request.get<unknown, TaskLog[]>(`/tasks/${taskId}/logs`)
}
