export type ImageStatus =
  | 'UPLOADING'
  | 'PARSING'
  | 'READY'
  | 'PROCESSING'
  | 'DELETE_LOCKED'
  | 'DELETED'
  | 'FAILED'

export type ImageVisibility = 'PRIVATE' | 'PUBLIC'

export type ThumbnailStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED'

export interface ImageSearchParams {
  keyword?: string
  startTime?: string
  endTime?: string
  sensor?: string
  maxCloudPercent?: number
  bbox?: string
  pageNum?: number
  pageSize?: number
}

export interface ImageRegionSearchParams {
  regionId: number
  startTime?: string
  endTime?: string
  sensor?: string
  maxCloudPercent?: number
  pageNum?: number
  pageSize?: number
}

export interface ImageListItem {
  id: number
  ownerId: string
  visibility: ImageVisibility
  imageCode: string
  imageName: string
  sensorType?: string
  acquisitionTime?: string
  cloudPercent?: number
  resolutionMeter?: number
  width?: number
  height?: number
  objectKey: string
  thumbnailObjectKey?: string
  thumbnailStatus?: ThumbnailStatus
  status: ImageStatus
  createdAt?: string
}

export interface ImageDetail extends ImageListItem {
  satelliteName?: string
  bandCount?: number
  projection?: string
  fileFormat?: string
  fileSize?: number
  contentType?: string
  metadataJson?: string
  minioBucket?: string
  overviewObjectKey?: string
  footprintWkt?: string
  centerLon?: number
  centerLat?: number
  description?: string
  thumbnailErrorMessage?: string
  updatedAt?: string
}

export interface ImageUploadParams {
  file: File
  name: string
  sensor?: string
  captureTime?: string
  cloudPercent?: number
}
