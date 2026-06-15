import request from '@/api/request'
import type {
  AiPlan,
  AiPlanListParams,
  AiPlanPageResult,
  AiQueryParseResult,
  AiTaskReport,
} from '@/types/ai'

export function parseAiQueryApi(text: string) {
  return request.post<unknown, AiQueryParseResult>('/ai/query/parse', { text })
}

export function generateTaskReportApi(taskId: number | string, silentError = false) {
  return request.post<unknown, AiTaskReport>(`/ai/reports/from-task/${taskId}`, undefined, { silentError })
}

export function createAiPlanApi(text: string) {
  return request.post<unknown, AiPlan>('/ai/plans', { text })
}

export function getAiPlanApi(id: number | string) {
  return request.get<unknown, AiPlan>(`/ai/plans/${id}`)
}

export function listAiPlansApi(params: AiPlanListParams) {
  return request.get<unknown, AiPlanPageResult>('/ai/plans', { params })
}

export function confirmAiPlanApi(id: number | string) {
  return request.patch<unknown, AiPlan>(`/ai/plans/${id}/confirm`)
}

export function cancelAiPlanApi(id: number | string) {
  return request.patch<unknown, AiPlan>(`/ai/plans/${id}/cancel`)
}
