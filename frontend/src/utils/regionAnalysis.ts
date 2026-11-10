import type { ImageDetail } from '@/types/image'
import type { TaskSubmitParams } from '@/types/task'

export const DEMO_IMAGE_NAME = '示例影像 · 鄱阳湖 2024-11-29'
export const DEMO_REGION_NAME = '示例区域 · 250 × 250 像素'

export function positiveId(value: unknown): number | undefined {
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : undefined
}

export function hasNdviAdmission(image: ImageDetail): boolean {
  try {
    const admission = JSON.parse(image.metadataJson || '{}').admission
    return admission?.status === 'PASSED' && admission?.validatorVersion === 'input-v1'
      && image.supportedTaskTypes?.includes('NDVI') === true
      && !!positiveId(image.bandMapping?.red) && !!positiveId(image.bandMapping?.nir)
  } catch { return false }
}

/** Both NDVI entry points submit the same existing backend contract. */
export function ndviPayload(imageId: number, redBand: number, nirBand: number, monitoringRegionId?: number): TaskSubmitParams {
  return { imageId, taskType: 'NDVI', ...(monitoringRegionId ? { monitoringRegionId } : {}), params: { redBand, nirBand } }
}

export function isUnauthorized(error: unknown): boolean {
  const value = error as { code?: number; response?: { status?: number } } | null
  return value?.code === 401 || value?.response?.status === 401
}

export function errorText(error: unknown, fallback = '暂时无法连接服务，请稍后重试'): string {
  const value = error as { response?: { data?: { message?: string } }; message?: string } | null
  return value?.response?.data?.message || value?.message || fallback
}
