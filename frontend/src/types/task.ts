export type TaskType = 'NDVI' | 'NDWI' | 'CHANGE_DETECTION'

export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'RETRYING' | 'CANCELED'

export interface TaskSubmitParams {
  imageId: number
  taskType: TaskType
  params?: Record<string, unknown>
}

export interface TaskSubmitResult {
  taskId: number
}

export interface TaskListItem {
  id: number
  ownerId: string
  taskCode: string
  imageId: number
  imageName: string
  taskType: TaskType
  taskName?: string
  status: TaskStatus
  progress?: number
  retryCount?: number
  maxRetryCount?: number
  inputBucket?: string
  inputObjectKey?: string
  outputBucket?: string
  outputObjectKey?: string
  params?: string
  errorMessage?: string
  submittedAt?: string
  startedAt?: string
  finishedAt?: string
}

export interface TaskDetail extends TaskListItem {
  retryCount?: number
  maxRetryCount?: number
  params?: string
  createdAt?: string
  updatedAt?: string
}

export interface TaskLog {
  id: number
  taskId: number
  logLevel: string
  message: string
  detail?: string
  createdAt?: string
}

export interface TaskPageParams {
  pageNum?: number
  pageSize?: number
}
