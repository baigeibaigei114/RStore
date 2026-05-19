import request from '@/api/request'
import type { AxiosProgressEvent } from 'axios'
import type { PageResult } from '@/types/api'
import type { FilePresignedUrl } from '@/types/file'
import type {
  ImageDetail,
  ImageListItem,
  ImageRegionSearchParams,
  ImageSearchParams,
  ImageUploadParams,
  ImageVisibility,
} from '@/types/image'

export function searchImagesApi(params: ImageSearchParams) {
  return request.get<unknown, PageResult<ImageListItem>>('/images/search', { params })
}

export function searchImagesByRegionApi(params: ImageRegionSearchParams) {
  return request.get<unknown, PageResult<ImageListItem>>('/images/search-by-region', { params })
}

export function getImageDetailApi(id: number | string) {
  return request.get<unknown, ImageDetail>(`/images/${id}`)
}

export function getImageDownloadUrlApi(imageId: number | string) {
  return request.get<unknown, FilePresignedUrl>(`/images/${imageId}/download-url`)
}

export function getImageThumbnailUrlApi(imageId: number | string) {
  return request.get<unknown, FilePresignedUrl>(`/images/${imageId}/thumbnail-url`)
}

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

export function updateImageVisibilityApi(id: number | string, visibility: ImageVisibility) {
  return request.patch<unknown, ImageDetail>(`/images/${id}/visibility`, { visibility })
}
