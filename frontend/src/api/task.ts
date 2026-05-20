import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { FilePresignedUrl } from '@/types/file'
import type {
  TaskDetail,
  TaskListItem,
  TaskLog,
  TaskPageParams,
  TaskResultFile,
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

export function getTaskResultFileApi(taskId: number | string) {
  return request.get<unknown, TaskResultFile>(`/tasks/${taskId}/result`)
}

export function getTaskResultDownloadUrlApi(taskId: number | string) {
  return request.get<unknown, FilePresignedUrl>(`/tasks/${taskId}/result/download-url`)
}
