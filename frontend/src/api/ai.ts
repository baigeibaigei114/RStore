import request from '@/api/request'
import type {
  AiQueryParseResult,
  AiTaskReport,
  TaskResultAnswer,
  TaskResultAnswerState,
  TaskResultQuestion,
} from '@/types/ai'

export function parseAiQueryApi(text: string) {
  return request.post<unknown, AiQueryParseResult>('/ai/query/parse', { text })
}

export function generateTaskReportApi(taskId: number | string, silentError = false) {
  return request.post<unknown, AiTaskReport>(`/ai/reports/from-task/${taskId}`, undefined, { silentError })
}

export function getTaskResultAnswerApi(taskId: string, signal: AbortSignal) {
  return request.get<unknown, TaskResultAnswerState>(`/agent/task-results/${taskId}/answers`, { signal, silentError: true })
}

export function askTaskResultApi(taskId: string, question: TaskResultQuestion, signal: AbortSignal) {
  return request.post<unknown, TaskResultAnswer>(`/agent/task-results/${taskId}/answers`, question,
    { signal, silentError: true, timeout: 45000 })
}
