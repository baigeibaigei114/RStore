/** 影像状态枚举：上传中 / 解析中 / 可用 / 处理中 / 删除锁定 / 已删除 / 失败 */
export type ImageStatus =
  | 'UPLOADING'
  | 'PARSING'
  | 'READY'
  | 'PROCESSING'
  | 'DELETE_LOCKED'
  | 'DELETED'
  | 'FAILED'

/** 影像可见性：PRIVATE（私有） / PUBLIC（公开） */
export type ImageVisibility = 'PRIVATE' | 'PUBLIC'

/** 缩略图生成状态：待处理 / 生成中 / 已生成 / 失败 / 已跳过 */
export type ThumbnailStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED'

/** 波段角色：红光、绿光、蓝光、近红外。 */
export type BandRole = 'red' | 'green' | 'blue' | 'nir'

/** 由可信波段映射支撑的影像处理任务类型。 */
export type SupportedImageTaskType = 'NDVI' | 'NDWI'

/** 影像分页搜索参数（按关键字/条件筛选） */
export interface ImageSearchParams {
  /** 搜索关键字，匹配影像名称或编码 */
  keyword?: string
  /** 采集时间范围起始，ISO 格式字符串 */
  startTime?: string
  /** 采集时间范围结束，ISO 格式字符串 */
  endTime?: string
  /** 传感器类型，如 Sentinel-2、Landsat 8 等 */
  sensor?: string
  /** 最大云量百分比，用于过滤云量过高的影像 */
  maxCloudPercent?: number
  /** 空间范围，WKT 格式的 BBOX 字符串，用于空间过滤 */
  bbox?: string
  /** 当前页码，从 1 开始 */
  pageNum?: number
  /** 每页记录数 */
  pageSize?: number
}

/** 按行政区划搜索影像的参数 */
export interface ImageRegionSearchParams {
  /** 行政区 ID（必填），用于按行政区划检索影像 */
  regionId: number
  /** 采集时间范围起始 */
  startTime?: string
  /** 采集时间范围结束 */
  endTime?: string
  /** 传感器类型 */
  sensor?: string
  /** 最大云量百分比 */
  maxCloudPercent?: number
  /** 当前页码 */
  pageNum?: number
  /** 每页记录数 */
  pageSize?: number
}

/** 影像列表摘要项（用于列表页展示） */
export interface ImageListItem {
  /** 影像唯一 ID */
  id: number
  /** 影像所属用户 ID */
  ownerId: string
  /** 可见性：公开或私有 */
  visibility: ImageVisibility
  /** 影像编码（系统生成的唯一标识） */
  imageCode: string
  /** 影像名称（用户自定义） */
  imageName: string
  /** 传感器类型 */
  sensorType?: string
  /** 采集时间，ISO 格式 */
  acquisitionTime?: string
  /** 云量百分比 */
  cloudPercent?: number
  /** 空间分辨率，单位米 */
  resolutionMeter?: number
  /** 影像宽度（像素） */
  width?: number
  /** 影像高度（像素） */
  height?: number
  /** MinIO 对象存储中的原始文件路径 */
  objectKey: string
  /** MinIO 对象存储中的缩略图文件路径 */
  thumbnailObjectKey?: string
  /** 缩略图生成状态 */
  thumbnailStatus?: ThumbnailStatus
  /** 影像处理状态 */
  status: ImageStatus
  /** 创建时间 */
  createdAt?: string
  /** 波段角色到实际波段编号的映射。 */
  bandMapping?: Partial<Record<BandRole, number>>
  /** 波段映射来源，如自动识别或用户确认。 */
  bandMappingSource?: string
  /** 波段映射置信度。 */
  bandMappingConfidence?: string
  /** 当前影像可直接提交的处理任务类型。 */
  supportedTaskTypes?: SupportedImageTaskType[]
}

/** 影像完整详情（继承 ImageListItem，包含全部元数据） */
export interface ImageDetail extends ImageListItem {
  /** 卫星名称 */
  satelliteName?: string
  /** 波段数量 */
  bandCount?: number
  /** 投影坐标系，如 EPSG:4326 */
  projection?: string
  /** 原始文件格式，如 GeoTIFF */
  fileFormat?: string
  /** 文件大小，单位字节 */
  fileSize?: number
  /** 文件 MIME 类型 */
  contentType?: string
  /** 原始元数据 JSON 字符串，包含传感器等原始信息 */
  metadataJson?: string
  /** MinIO 存储桶名称 */
  minioBucket?: string
  /** 金字塔概览文件在 MinIO 中的路径 */
  overviewObjectKey?: string
  /** 空间范围，WKT 格式（常用于地图上绘制足迹） */
  footprintWkt?: string
  /** 中心点经度 */
  centerLon?: number
  /** 中心点纬度 */
  centerLat?: number
  /** 影像描述 */
  description?: string
  /** 缩略图生成错误信息 */
  thumbnailErrorMessage?: string
  /** 删除时间 */
  deletedAt?: string
  /** 删除原因 */
  deletedReason?: string
  /** 更新时间 */
  updatedAt?: string
}

/** 影像上传参数 */
export interface ImageUploadParams {
  /** 上传的文件对象 */
  file: File
  /** 影像名称 */
  name: string
  /** 可选，传感器类型 */
  sensor?: string
  /** 可选，采集时间 */
  captureTime?: string
  /** 可选，云量百分比 */
  cloudPercent?: number
}

export interface ImageBandMappingUpdateParams {
  /** 红光波段编号。 */
  redBand?: number
  /** 绿光波段编号。 */
  greenBand?: number
  /** 蓝光波段编号。 */
  blueBand?: number
  /** 近红外波段编号。 */
  nirBand?: number
}
