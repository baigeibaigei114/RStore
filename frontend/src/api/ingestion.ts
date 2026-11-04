import request from '@/api/request'
import type { ConfirmationView, ConfirmRequest, CreateProduct, CreatedProduct, JobPage, JobRetryRequest, JobSubmission, JobView, PreflightRequest, ProductPage, ProductView, RetryRequest } from '@/types/ingestion'

export const createProduct = (body: CreateProduct) => request.post<unknown, CreatedProduct>('/ingestion/products', body, { silentError: true })
export const getProduct = (id: string) => request.get<unknown, ProductView>(`/ingestion/products/${id}`, { silentError: true })
export const listProducts = (page = 1) => request.get<unknown, ProductPage>('/ingestion/products', { params: { page, pageSize: 20 }, silentError: true })
export const retryProduct = (id: string, body: RetryRequest) => request.post<unknown, { productId: string; status: string; version: number }>(`/ingestion/products/${id}/retry`, body, { silentError: true })
export const createPreflight = (body: PreflightRequest) => request.post<unknown, { confirmationId: string; status: string; version: number }>('/ingestion/confirmations', body, { silentError: true })
export const getPreflight = (id: string) => request.get<unknown, ConfirmationView>(`/ingestion/confirmations/${id}`, { silentError: true })
export const confirmPreflight = (id: string, body: ConfirmRequest) => request.post<unknown, JobSubmission>(`/ingestion/confirmations/${id}/confirm`, body, { silentError: true })
export const getIngestionJob = (id: string) => request.get<unknown, JobView>(`/ingestion/jobs/${id}`, { silentError: true })
export const listIngestionJobs = () => request.get<unknown, JobPage>('/ingestion/jobs', { params: { page: 1, pageSize: 20 }, silentError: true })
export const retryIngestionJob = (id: string, body: JobRetryRequest) => request.post<unknown, JobSubmission>(`/ingestion/jobs/${id}/retry`, body, { silentError: true })
export const previewBlob = (id: string, signal?: AbortSignal) => request.get<unknown, Blob>(`/ingestion/products/${id}/preview`, { responseType: 'blob', signal, silentError: true })
export const uploadProduct = (id: string, file: File, progress: (percent: number) => void, signal?: AbortSignal) =>
  request.put<unknown, { productId: string; status: string; version: number }>(`/ingestion/products/${id}/content`, file, {
    headers: { 'Content-Type': 'application/zip' }, timeout: 25 * 60 * 1000, signal, silentError: true,
    onUploadProgress: event => progress(Math.min(100, Math.floor(event.loaded / file.size * 100))),
  })

const messages: Record<string, string> = {
  SERVICE_UNAVAILABLE: '服务暂不可用，输入已保留，请稍后刷新状态。', CAPACITY_BUSY: '处理名额已占用，请稍后再试。',
  WORKER_RECOVERY_REQUIRED: '计算服务正在恢复，请稍后查询。', QUOTA_EXCEEDED: '存储或待处理容量不足，请检查后再试。',
  INVALID_REQUEST: '请求内容不符合要求，请检查输入。', INVALID_ARCHIVE: '无法识别完整的官方 SAFE ZIP，请检查产品包。',
  UNSUPPORTED_PRODUCT: '当前支持 PSD15 / PB05.13 的单产品、单 granule Sentinel-2 L2A。',
  UPLOAD_TOO_LARGE: '文件超过 2 GiB 上传上限。', UPLOAD_EXPIRED: '上传已超时，请刷新状态后从头重传。',
  RAW_EXPIRED: '原包已到期，请重新上传产品。', REGION_CHANGED: '区域已被修改，请重新读取区域并预检。',
  REGION_OUTSIDE_PRODUCT: '区域没有完整落在产品内，请调整边界。', WINDOW_TOO_LARGE: '裁剪窗口超过 2048 像素，请缩小区域。',
  INVALID_GRID: '产品网格不符合当前支持范围。', INPUT_HASH_MISMATCH: '文件或处理依据不一致，本次处理已拒绝。',
  STATE_CONFLICT: '状态已变化，请明确刷新后再决定下一步。', REQUEST_KEY_CONFLICT: '重试内容与原请求不一致，请先恢复原请求。',
  NO_VALID_PIXELS: '产品预览或选区没有有效像素。', NOT_FOUND: '记录不存在或不属于当前用户。',
  CONFIRMATION_STALE: '确认依据已变化，请重新读取后预检。', CONFIRMATION_EXPIRED: '确认已到期，请重新预检。',
  ADMISSION_REJECTED: '候选影像未通过实际文件验证，没有登记新资产。', ATTEMPT_STALE: '当前执行已失效，请重新读取作业状态。',
  ASSET_UNAVAILABLE: '已登记影像已删除或不可用，不能复制原作业生成第二份资产。', ASSET_CHANGED: '已登记影像的准入信息已变化，请核对资产。',
  HARD_TIMEOUT: '本阶段超过时限，请查询失败记录后决定是否重试。', LEASE_LOST: '本次执行已失去租约，等待停止和状态收敛。',
  DISPATCH_FAILED: '工作投递未完成，请查询作业状态。',
}
export function ingestionMessage(reason?: string) { return reason ? messages[reason] || '本阶段未完成，请查看状态后重试。' : '' }
export function describeIngestionError(error: unknown): { status?: number; message: string } {
  const e = error as { response?: { status: number; data?: { data?: { reason?: string } } } }
  const status = e.response?.status
  return { status, message: status === 401 ? '登录已失效，输入已保留，请重新登录。' : ingestionMessage(e.response?.data?.data?.reason) || '网络结果尚未确认，输入已保留，请先查询状态。' }
}
