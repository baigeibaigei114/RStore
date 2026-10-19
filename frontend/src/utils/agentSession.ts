import type { AgentExecution, PendingAction } from '../types/agent'

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const ID_PATTERN = /^[1-9]\d{0,18}$/
interface SessionStorage { getItem(key: string): string | null; setItem(key: string, value: string): void; removeItem(key: string): void }
const bodyFields = {
  create: ['clientRequestId', 'text'], message: ['clientRequestId', 'expectedVersion', 'text', 'selectedImageId', 'selectedRegionId'],
  confirm: ['draftVersion', 'draftHash'], explanation: ['clientRequestId'], retry: ['clientRequestId', 'expectedVersion'], cancel: ['expectedVersion'],
}

function validPending(value: unknown): value is PendingAction {
  if (!value || typeof value !== 'object') return false
  const item = value as Record<string, unknown>
  const kind = item.kind as keyof typeof bodyFields
  if (!Object.hasOwn(bodyFields, kind) || !item.body || typeof item.body !== 'object' || Array.isArray(item.body)) return false
  if (Object.keys(item).some(key => !['kind', 'executionId', 'body'].includes(key))) return false
  if (kind !== 'create' && (typeof item.executionId !== 'string' || !UUID_PATTERN.test(item.executionId))) return false
  const body = item.body as Record<string, unknown>
  if (Object.keys(body).some(key => !bodyFields[kind].includes(key))) return false
  if (['create', 'message', 'retry', 'explanation'].includes(kind)
    && (typeof body.clientRequestId !== 'string' || !UUID_PATTERN.test(body.clientRequestId))) return false
  if (['message', 'retry', 'cancel'].includes(kind) && (!Number.isSafeInteger(body.expectedVersion) || Number(body.expectedVersion) < 1)) return false
  if (kind === 'confirm' && (!Number.isSafeInteger(body.draftVersion) || Number(body.draftVersion) < 1
    || typeof body.draftHash !== 'string' || !/^[a-f0-9]{64}$/.test(body.draftHash))) return false
  if (kind === 'create' && (typeof body.text !== 'string' || !body.text.trim())) return false
  if (body.text !== undefined && (typeof body.text !== 'string' || [...body.text].length > 2000)) return false
  for (const key of ['selectedImageId', 'selectedRegionId']) if (body[key] !== undefined && (typeof body[key] !== 'string' || !ID_PATTERN.test(body[key]))) return false
  if (kind === 'message' && !body.text && !body.selectedImageId && !body.selectedRegionId) return false
  return true
}

/** One unresolved write per authenticated user/tab. Never auto-replay after refresh. */
export class PendingActionStore {
  private storage: SessionStorage
  private key: string
  constructor(storage: SessionStorage, owner: string) {
    if (!owner || owner.length > 100) throw new Error('当前登录身份不可用')
    this.storage = storage
    this.key = `ndvi-agent:${encodeURIComponent(owner)}:pending`
  }
  read(): PendingAction | null {
    const raw = this.storage.getItem(this.key)
    if (!raw) return null
    try {
      if (raw.length > 20000) throw new Error('Invalid pending action')
      const parsed: unknown = JSON.parse(raw)
      if (!validPending(parsed)) throw new Error('Invalid pending action')
      return parsed
    } catch { this.storage.removeItem(this.key); return null }
  }
  save(action: PendingAction) {
    if (!validPending(action)) throw new Error('请求参数不完整，请刷新后重新选择')
    // Persist before starting HTTP. Quota/privacy-mode failure must not silently lose the request key.
    this.storage.setItem(this.key, JSON.stringify(action))
  }
  clear() { this.storage.removeItem(this.key) }
}

/** A route/account epoch invalidates all earlier responses, even when transport abort arrives late. */
export class RequestEpoch {
  private value = 0
  next() { return ++this.value }
  current() { return this.value }
  accepts(value: number) { return value === this.value }
}
export function shouldPoll(view: Pick<AgentExecution, 'status'> | null) {
  return !!view && ['RUNNING', 'WAITING_TASK', 'EXPLAINING'].includes(view.status)
}
export function canApplyView(selectedId: string, current: AgentExecution | null, incoming: AgentExecution) {
  return incoming.executionId === selectedId && (!current || current.executionId !== incoming.executionId || incoming.version >= current.version)
}
export function resolvedByQuery(action: PendingAction, view: AgentExecution) {
  if (action.kind === 'create' || action.executionId !== view.executionId) return false
  return (action.kind === 'confirm' && !!view.task) || (action.kind === 'cancel' && view.status === 'CANCELED')
    || (action.kind === 'explanation' && !!view.explanation)
}
export function safeSourceUrl(value: string | null | undefined): string | null {
  if (!value) return null
  try { const url = new URL(value); return url.protocol === 'https:' && !url.username && !url.password ? url.href : null }
  catch { return null }
}
