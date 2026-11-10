import type { TaskSubmitParams } from '@/types/task'

export interface AnalysisIntent {
  version: 1
  accountId: string
  payload: TaskSubmitParams
  taskId?: number
}

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
const key = (account: string) => `rs-region-analysis:v1:${encodeURIComponent(account)}`

export function saveIntent(store: Store, account: string, payload: TaskSubmitParams, taskId?: number): AnalysisIntent {
  const intent: AnalysisIntent = { version: 1, accountId: account, payload: JSON.parse(JSON.stringify(payload)),
    ...(taskId ? { taskId } : {}) }
  // Persist before POST. A storage failure must prevent a new write.
  try { store.setItem(key(account), JSON.stringify(intent)) }
  catch { throw new Error('浏览器无法保存本次请求，未提交分析。请允许会话存储后重试。') }
  return intent
}

export function readIntent(store: Store, account: string): AnalysisIntent | null {
  let raw: string | null
  try { raw = store.getItem(key(account)) }
  catch { throw new Error('浏览器无法读取已保存的请求。请允许会话存储后刷新页面，再继续分析。') }
  if (!raw) return null
  try {
    const value = JSON.parse(raw) as AnalysisIntent
    const payload = value.payload
    if (value.version !== 1 || value.accountId !== account || payload?.taskType !== 'NDVI'
      || !Number.isSafeInteger(payload.imageId) || payload.imageId <= 0
      || !Number.isSafeInteger(payload.monitoringRegionId) || (payload.monitoringRegionId ?? 0) <= 0
      || typeof payload.clientRequestId !== 'string' || !payload.clientRequestId
      || (value.taskId !== undefined && (!Number.isSafeInteger(value.taskId) || value.taskId <= 0))) return null
    return value
  } catch { return null }
}

export function clearIntent(store: Store, account: string) {
  try { store.removeItem(key(account)) }
  catch { throw new Error('浏览器无法更新本次请求记录，原记录已保留。请允许会话存储后重试。') }
}

export function definiteRejection(error: unknown): boolean {
  const value = error as { code?: unknown; response?: { status?: number; data?: { code?: number } } } | null
  const code = typeof value?.code === 'number' ? value.code : value?.response?.data?.code ?? value?.response?.status
  return typeof code === 'number' && [400, 403, 404, 409, 422, 429].includes(code)
}
