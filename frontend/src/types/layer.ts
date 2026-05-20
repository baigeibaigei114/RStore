import type { ImageVisibility } from '@/types/image'
import type { TaskType } from '@/types/task'

export interface LayerListItem {
  id: number
  taskId: number
  imageId: number
  imageName?: string
  imageDeletedAt?: string
  taskType?: TaskType | string
  taskName?: string
  fileName?: string
  fileType?: string
  ownerId?: string
  visibility?: ImageVisibility
  workspace?: string
  storeName?: string
  layerName?: string
  qualifiedLayerName?: string
  proxyWmsUrl?: string
  proxyWcsUrl?: string
  publishedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface LayerSearchParams {
  pageNum?: number
  pageSize?: number
  taskType?: string
  imageId?: number
  keyword?: string
}
