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

/**
 * 创建解译任务接口
 * 为指定影像提交 NDVI/NDWI/变化检测等类型的处理任务
 * @param params - 任务提交参数，包含 imageId（影像 ID）、taskType（任务类型）及可选的自定义 params
 * @returns Promise<TaskSubmitResult> - 包含新创建的 taskId
 */
export function createTaskApi(params: TaskSubmitParams) {
  return request.post<unknown, TaskSubmitResult>('/tasks', params)
}

/**
 * 分页查询任务列表接口
 * 按分页参数获取当前用户的任务列表
 * @param params - 分页参数，包含 pageNum 和 pageSize
 * @returns Promise<PageResult<TaskListItem>> - 分页的任务摘要列表
 */
export function listTasksApi(params: TaskPageParams) {
  return request.get<unknown, PageResult<TaskListItem>>('/tasks', { params })
}

/**
 * 获取任务详情接口
 * 返回指定任务的完整信息，包括状态、进度、输入输出对象路径、参数等
 * @param taskId - 任务 ID（数字或字符串）
 * @returns Promise<TaskDetail> - 任务完整详情
 */
export function getTaskDetailApi(taskId: number | string) {
  return request.get<unknown, TaskDetail>(`/tasks/${taskId}`)
}

/**
 * 获取任务日志列表接口
 * 返回指定任务的全部处理日志，按时间顺序排列
 * @param taskId - 任务 ID
 * @returns Promise<TaskLog[]> - 任务日志数组
 */
export function listTaskLogsApi(taskId: number | string) {
  return request.get<unknown, TaskLog[]>(`/tasks/${taskId}/logs`)
}

/**
 * 获取任务结果文件信息接口
 * 返回任务处理结果的发布信息，包括图层名称、WMS/WCS 地址等
 * 可通过 silentError 控制是否静默处理错误（默认为 false）
 * @param taskId - 任务 ID
 * @param silentError - 是否静默处理 404 等错误，默认 false
 * @returns Promise<TaskResultFile> - 结果文件发布信息
 */
export function getTaskResultFileApi(taskId: number | string, silentError = false) {
  return request.get<unknown, TaskResultFile>(`/tasks/${taskId}/result`, { silentError })
}

/**
 * 获取任务结果下载地址接口
 * 返回预签名的结果文件下载 URL，可直接在浏览器中访问下载
 * @param taskId - 任务 ID
 * @returns Promise<FilePresignedUrl> - 包含预签名下载 URL 的对象
 */
export function getTaskResultDownloadUrlApi(taskId: number | string) {
  return request.get<unknown, FilePresignedUrl>(`/tasks/${taskId}/result/download-url`)
}
