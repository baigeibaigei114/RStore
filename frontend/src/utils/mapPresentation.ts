import type { LayerListItem } from '@/types/layer'
import type { TaskDetail, TaskResultFile } from '@/types/task'

export function mapAnalysisLabel(type?: string) {
  return ({ NDVI: '植被绿度', NDWI: '水体指数', CHANGE_DETECTION: '地表变化' } as Record<string, string>)[type || ''] || '分析结果'
}

export function mapResultTitle(result: LayerListItem) {
  // Technical names are transport parameters, never a user-facing fallback.
  return result.imageName?.trim() || result.taskName?.trim() || '未命名影像'
}

export function mapResultDetail(result: LayerListItem) {
  const date = result.publishedAt || result.createdAt
  const time = date ? date.replace('T', ' ').slice(0, 19) : '已可查看'
  return `${mapAnalysisLabel(result.taskType)} · ${time}`
}

export function mapTaskResult(task: Pick<TaskDetail, 'id' | 'imageId' | 'imageName' | 'taskType' | 'taskName'>, result: TaskResultFile): LayerListItem | null {
  if (result.taskId !== task.id || (result.imageId !== undefined && result.imageId !== task.imageId)
    || result.status !== 'PUBLISHED' || !result.layerName
    || !Number.isSafeInteger(result.id) || result.id <= 0) return null
  return { id: result.id, taskId: task.id, imageId: task.imageId, imageName: task.imageName,
    taskType: task.taskType, taskName: task.taskName, publishedAt: result.publishedAt, createdAt: result.createdAt,
    layerName: result.layerName, qualifiedLayerName: result.layerName.includes(':') || !result.workspace
      ? result.layerName : `${result.workspace}:${result.layerName}` }
}
