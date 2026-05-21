/** 解译任务类型：NDVI（植被指数）/ NDWI（水体指数）/ CHANGE_DETECTION（变化检测） */
export type TaskType = 'NDVI' | 'NDWI' | 'CHANGE_DETECTION'

/** 任务状态：待处理 / 运行中 / 成功 / 失败 / 重试中 / 已取消 */
export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'RETRYING' | 'CANCELED'

/** 提交解译任务的请求参数 */
export interface TaskSubmitParams {
  /** 待处理的影像 ID */
  imageId: number
  /** 解译任务类型 */
  taskType: TaskType
  /** 可选的自定义参数，不同类型任务可能有额外配置 */
  params?: Record<string, unknown>
}

/** 提交任务后的返回结果 */
export interface TaskSubmitResult {
  /** 创建成功的任务 ID */
  taskId: number
}

/** 任务列表摘要项 */
export interface TaskListItem {
  /** 任务唯一 ID */
  id: number
  /** 任务所属用户 ID */
  ownerId: string
  /** 任务编码（系统生成的唯一标识） */
  taskCode: string
  /** 关联的影像 ID */
  imageId: number
  /** 关联的影像名称 */
  imageName: string
  /** 解译任务类型 */
  taskType: TaskType
  /** 任务名称（用户自定义） */
  taskName?: string
  /** 任务当前状态 */
  status: TaskStatus
  /** 处理进度百分比 0-100 */
  progress?: number
  /** 当前已重试次数 */
  retryCount?: number
  /** 最大重试次数 */
  maxRetryCount?: number
  /** 输入文件所在的 MinIO 存储桶 */
  inputBucket?: string
  /** 输入文件在 MinIO 中的对象键 */
  inputObjectKey?: string
  /** 输出结果所在 MinIO 存储桶 */
  outputBucket?: string
  /** 输出结果在 MinIO 中的对象键 */
  outputObjectKey?: string
  /** 任务参数字符串（JSON 格式） */
  params?: string
  /** 任务失败时的错误信息 */
  errorMessage?: string
  /** 任务提交时间 */
  submittedAt?: string
  /** 任务开始处理时间 */
  startedAt?: string
  /** 任务完成时间 */
  finishedAt?: string
}

/** 任务完整详情（继承 TaskListItem） */
export interface TaskDetail extends TaskListItem {
  /** 当前已重试次数 */
  retryCount?: number
  /** 最大重试次数 */
  maxRetryCount?: number
  /** 任务参数 JSON 字符串 */
  params?: string
  /** 记录创建时间 */
  createdAt?: string
  /** 记录更新时间 */
  updatedAt?: string
}

/** 任务结果文件信息，包含发布到 GeoServer 的图层详情 */
export interface TaskResultFile {
  /** 结果文件记录 ID */
  id: number
  /** 所属用户 ID */
  ownerId?: string
  /** 可见性：公开或私有 */
  visibility?: 'PRIVATE' | 'PUBLIC'
  /** 关联的任务 ID */
  taskId: number
  /** 关联的影像 ID */
  imageId?: number
  /** 结果文件名 */
  fileName?: string
  /** 结果文件类型 */
  fileType?: string
  /** MinIO 存储桶 */
  minioBucket?: string
  /** MinIO 对象键 */
  objectKey?: string
  /** 文件大小，单位字节 */
  fileSize?: number
  /** 文件 MIME 类型 */
  mimeType?: string
  /** 文件校验和 */
  checksum?: string
  /** 结果元数据 JSON 字符串 */
  resultMetadata?: string
  /** 发布状态：待发布 / 发布中 / 已发布 / 失败 */
  status?: 'PENDING' | 'PUBLISHING' | 'PUBLISHED' | 'FAILED' | string
  /** GeoServer 工作空间名称 */
  workspace?: string
  /** GeoServer 存储名称 */
  storeName?: string
  /** GeoServer 图层名称 */
  layerName?: string
  /** 图层的 WMS 服务访问地址 */
  wmsUrl?: string
  /** 图层的 WCS 服务访问地址 */
  wcsUrl?: string
  /** 发布过程中的错误信息 */
  publishErrorMessage?: string
  /** 发布时间 */
  publishedAt?: string
  /** 记录创建时间 */
  createdAt?: string
  /** 记录更新时间 */
  updatedAt?: string
}

/** 任务处理日志条目 */
export interface TaskLog {
  /** 日志记录 ID */
  id: number
  /** 所属任务 ID */
  taskId: number
  /** 日志级别，如 INFO / WARN / ERROR */
  logLevel: string
  /** 日志消息摘要 */
  message: string
  /** 日志详细内容（可能为堆栈跟踪或详细描述） */
  detail?: string
  /** 日志记录时间 */
  createdAt?: string
}

/** 任务列表分页查询参数 */
export interface TaskPageParams {
  /** 当前页码，从 1 开始 */
  pageNum?: number
  /** 每页记录数 */
  pageSize?: number
}
