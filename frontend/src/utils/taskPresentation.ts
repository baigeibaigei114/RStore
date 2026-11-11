import type { TaskDetail, TaskResultFile, TaskStatus, TaskType } from '@/types/task'

const statusLabels: Record<string, string> = { PENDING: '等待处理', RUNNING: '正在计算',
  RETRYING: '正在自动重试', SUCCESS: '计算完成', FAILED: '计算失败', CANCELED: '已取消' }
export const taskStatusText = (status?: TaskStatus) => statusLabels[status || ''] || '正在读取状态'
export const isActiveStatus = (status?: TaskStatus) => !!status && ['PENDING', 'RUNNING', 'RETRYING'].includes(status)

export function failureMessage(message?: string, type?: TaskType): string {
  if (type === 'CHANGE_DETECTION' && message?.includes('no common valid pixels')) {
    return '两期影像没有共同有效像素。请核对所选波段和有效范围。'
  }
  if (message?.includes('not fully covered')) return '监测区域没有完整落在影像范围内。请调整区域，或换一景覆盖该区域的影像。'
  if (message && /no valid.*pixel|no pixel.*center|zero.*pixel/i.test(message)) {
    if (type === 'NDWI') return '没有可用于 NDWI 的像素。请核对绿光和近红外波段，或选择另一景影像。'
    return '所选区域没有可用于 NDVI 的有效像素。请调整区域或换一景影像。'
  }
  return '本次计算未能完成。请先核对影像和区域；具体原因可在技术详情中查看。'
}

export interface IndexStatistics {
  totalPixelCount: number; validPixelCount: number; invalidPixelCount: number
  validPixelRatio: number; min: number; max: number; mean: number
}

export function regionSnapshot(task: Pick<TaskDetail, 'params'> | null) {
  try { return JSON.parse(task?.params || '{}').regionSnapshot || null } catch { return null }
}

function canonical(value: unknown): string {
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']'
  if (value && typeof value === 'object') return '{' + Object.entries(value).sort(([a], [b]) => a.localeCompare(b))
    .map(([key, item]) => JSON.stringify(key) + ':' + canonical(item)).join(',') + '}'
  return JSON.stringify(value)
}

export function readStatistics(task: TaskDetail | null, result: TaskResultFile | null): IndexStatistics | null {
  if (!task || task.status !== 'SUCCESS' || !['NDVI', 'NDWI', 'CHANGE_DETECTION'].includes(task.taskType) ||
    !result?.resultMetadata || result.taskId !== task.id) return null
  try {
    const metadata = JSON.parse(result.resultMetadata)
    const stats = metadata.statistics as IndexStatistics
    const region = task.taskType === 'NDVI' ? regionSnapshot(task) : null
    if (metadata.algorithm !== task.taskType
      || (region ? metadata.schemaVersion !== 2 || metadata.scope !== 'monitoring_region'
        || canonical(metadata.regionSnapshot) !== canonical(region)
        : metadata.schemaVersion !== 1 || metadata.scope !== 'cropped_raster')) return null
    if (!stats || !Object.values(stats).every(value => typeof value === 'number' && Number.isFinite(value))) return null
    const counts = [stats.totalPixelCount, stats.validPixelCount, stats.invalidPixelCount]
    if (!counts.every(value => Number.isSafeInteger(value) && value >= 0)
      || stats.totalPixelCount <= 0 || stats.validPixelCount <= 0
      || stats.validPixelCount + stats.invalidPixelCount !== stats.totalPixelCount
      || ![stats.min, stats.max, stats.mean, stats.validPixelRatio].every(Number.isFinite)
      || stats.min > stats.mean || stats.mean > stats.max
      || Math.abs(stats.validPixelRatio - stats.validPixelCount / stats.totalPixelCount) > 1e-9) return null
    return stats
  } catch { return null }
}

export function readChangeSummary(task: TaskDetail | null, result: TaskResultFile | null) {
  if (task?.taskType !== 'CHANGE_DETECTION' || !result?.resultMetadata) return null
  const base = readStatistics(task, result)
  if (!base) return null
  try {
    const metadata = JSON.parse(result.resultMetadata)
    const { changedPixelCount: changed, unchangedPixelCount: unchanged, changeRatio: ratio } = metadata.statistics
    const threshold = metadata.comparison?.threshold
    if (metadata.grid?.alignment !== 'same_grid' || !Number.isSafeInteger(changed) || changed < 0 ||
      !Number.isSafeInteger(unchanged) || unchanged < 0 || changed + unchanged !== base.validPixelCount ||
      !Number.isFinite(ratio) || Math.abs(ratio - changed / base.validPixelCount) > 1e-9 ||
      !Number.isFinite(threshold) || threshold < 0) return null
    return { changed, valid: base.validPixelCount, total: base.totalPixelCount, ratio, threshold }
  } catch { return null }
}

/** One timer and one in-flight request; callers stop the controller on route/account changes. */
export function createRefreshController(work: () => Promise<boolean>, schedule = setTimeout, cancel = clearTimeout, onStopped = () => {}) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let busy = false
  let stopped = true
  let epoch = 0
  let failures = 0
  let attempts = 0
  async function tick() {
    if (stopped || busy) return
    if (timer !== undefined) cancel(timer)
    timer = undefined
    busy = true
    const current = epoch
    let again = true
    try { again = await work(); failures = 0 } catch { failures++ }
    finally { busy = false }
    if (current !== epoch) { if (!stopped) void tick(); return }
    if (stopped) return
    attempts++
    if (again && attempts < 60 && failures < 5) timer = schedule(tick, Math.min(3000 * 2 ** failures, 30000))
    else { stopped = true; onStopped() }
  }
  return {
    start() { if (stopped) { stopped = false; epoch++; attempts = 0; failures = 0 }; void tick() },
    stop() { stopped = true; epoch++; if (timer !== undefined) cancel(timer); timer = undefined },
    get running() { return !stopped },
  }
}
