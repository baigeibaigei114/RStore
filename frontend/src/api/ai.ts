import request from '@/api/request'
import type {
  AiQueryParseResult,
  AiTaskReport,
} from '@/types/ai'

export function parseAiQueryApi(text: string) {
  return request.post<unknown, AiQueryParseResult>('/ai/query/parse', { text })
}

export function generateTaskReportApi(taskId: number | string, silentError = false) {
  return request.post<unknown, AiTaskReport>(`/ai/reports/from-task/${taskId}`, undefined, { silentError })
}
