import request from '@/api/request'
import type { AxiosProgressEvent } from 'axios'
import type { PageResult } from '@/types/api'
import type { FilePresignedUrl } from '@/types/file'
import type {
  ImageDetail,
  ImageBandMappingUpdateParams,
  ImageListItem,
  ImageRegionSearchParams,
  ImageSearchParams,
  ImageUploadParams,
  ImageVisibility,
} from '@/types/image'

/**
 * 分页搜索影像列表接口
 * 支持按关键字、传感器、云量、采集时间范围和空间范围（bbox）筛选
 * @param params - 搜索条件，包含 keyword/sensor/maxCloudPercent/startTime/endTime/bbox/分页参数
 * @returns Promise<PageResult<ImageListItem>> - 分页的影像摘要列表
 */
export function searchImagesApi(params: ImageSearchParams) {
  return request.get<unknown, PageResult<ImageListItem>>('/images/search', { params })
}

/**
 * 按行政区划搜索影像接口
 * 基于 regionId（行政区 ID）和可选的时间、传感器、云量过滤条件进行检索
 * @param params - 搜索条件，包含 regionId（必填）及可选的传感器、时间范围、云量上限、分页参数
 * @returns Promise<PageResult<ImageListItem>> - 分页的影像摘要列表
 */
export function searchImagesByRegionApi(params: ImageRegionSearchParams) {
  return request.get<unknown, PageResult<ImageListItem>>('/images/search-by-region', { params })
}

/**
 * 获取影像详情接口
 * 返回影像完整的元数据信息，包括空间范围、存储路径、传感器参数等
 * @param id - 影像 ID（数字或字符串）
 * @returns Promise<ImageDetail> - 影像完整详情
 */
export function getImageDetailApi(id: number | string) {
  return request.get<unknown, ImageDetail>(`/images/${id}`)
}

/**
 * 获取影像原始文件下载地址接口
 * 返回一个预签名的临时下载 URL（MinIO 预签名 URL），可直连对象存储下载
 * @param imageId - 影像 ID
 * @returns Promise<FilePresignedUrl> - 包含预签名 URL 的对象
 */
export function getImageDownloadUrlApi(imageId: number | string) {
  return request.get<unknown, FilePresignedUrl>(`/images/${imageId}/download-url`)
}

/**
 * 获取影像缩略图地址接口
 * 返回一个预签名的缩略图访问 URL
 * @param imageId - 影像 ID
 * @returns Promise<FilePresignedUrl> - 包含缩略图预签名 URL 的对象
 */
export function getImageThumbnailUrlApi(imageId: number | string) {
  return request.get<unknown, FilePresignedUrl>(`/images/${imageId}/thumbnail-url`)
}

/**
 * 上传影像文件接口
 * 以 multipart/form-data 格式上传文件及元数据，支持上传进度回调
 * @param params - 上传参数，包含 file（文件对象）、name（影像名称）及可选的 sensor、captureTime、cloudPercent
 * @param onUploadProgress - 可选的上传进度回调函数，接收 AxiosProgressEvent 事件
 * @returns Promise<ImageDetail> - 上传成功后返回影像完整详情
 */
export function uploadImageApi(
  params: ImageUploadParams,
  onUploadProgress?: (event: AxiosProgressEvent) => void,
) {
  const formData = new FormData()
  formData.append('file', params.file)
  formData.append('name', params.name)

  if (params.sensor) {
    formData.append('sensor', params.sensor)
  }
  if (params.captureTime) {
    formData.append('captureTime', params.captureTime)
  }
  if (params.cloudPercent !== undefined && params.cloudPercent !== null) {
    formData.append('cloudPercent', String(params.cloudPercent))
  }

  return request.post<unknown, ImageDetail>('/images/upload', formData, {
    onUploadProgress,
  })
}

/**
 * 更新影像可见性接口
 * 切换影像为公开（PUBLIC）或私有（PRIVATE）状态
 * @param id - 影像 ID
 * @param visibility - 目标可见性状态 'PRIVATE' | 'PUBLIC'
 * @returns Promise<ImageDetail> - 更新后的影像详情
 */
export function updateImageVisibilityApi(id: number | string, visibility: ImageVisibility) {
  return request.patch<unknown, ImageDetail>(`/images/${id}/visibility`, { visibility })
}

/**
 * 手动确认影像波段映射。
 * 用于系统无法自动识别 red/green/blue/nir 时，由用户保存自己确认的波段编号。
 */
export function updateImageBandMappingApi(id: number | string, params: ImageBandMappingUpdateParams) {
  return request.patch<unknown, ImageDetail>(`/images/${id}/band-mapping`, params)
}

/**
 * 删除影像接口
 * 逻辑删除指定影像，删除后从列表和空间检索中隐藏，历史任务记录仍保留
 * @param id - 影像 ID
 * @returns Promise<void>
 */
export function deleteImageApi(id: number | string) {
  return request.delete<unknown, void>(`/images/${id}`)
}
