import type { ImageVisibility } from '@/types/image'
import type { TaskType } from '@/types/task'

/** 已发布图层列表项（任务结果发布到 GeoServer 后的图层信息） */
export interface LayerListItem {
  /** 图层记录 ID */
  id: number
  /** 关联的任务 ID */
  taskId: number
  /** 关联的影像 ID */
  imageId: number
  /** 影像名称 */
  imageName?: string
  /** 影像删除时间（若影像已被删除则不为空） */
  imageDeletedAt?: string
  /** 任务类型 */
  taskType?: TaskType | string
  /** 任务名称 */
  taskName?: string
  /** 结果文件名 */
  fileName?: string
  /** 结果文件类型 */
  fileType?: string
  /** 所属用户 ID */
  ownerId?: string
  /** 可见性：公开或私有 */
  visibility?: ImageVisibility
  /** GeoServer 工作空间名称 */
  workspace?: string
  /** GeoServer 存储名称 */
  storeName?: string
  /** GeoServer 图层名称 */
  layerName?: string
  /** 完全限定图层名称，格式为 workspace:layerName */
  qualifiedLayerName?: string
  /** WMS 代理 URL（通过后端代理访问 GeoServer） */
  proxyWmsUrl?: string
  /** WCS 代理 URL（通过后端代理访问 GeoServer） */
  proxyWcsUrl?: string
  /** 发布时间 */
  publishedAt?: string
  /** 记录创建时间 */
  createdAt?: string
  /** 记录更新时间 */
  updatedAt?: string
}

/** 图层分页搜索参数 */
export interface LayerSearchParams {
  /** 当前页码 */
  pageNum?: number
  /** 每页记录数 */
  pageSize?: number
  /** 按任务类型过滤：NDVI / NDWI / CHANGE_DETECTION */
  taskType?: string
  /** 按影像 ID 过滤 */
  imageId?: number
  /** 按关键字搜索（影像名、任务名或图层名） */
  keyword?: string
}
