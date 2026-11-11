import request from '@/api/request'
import type {
  AiQueryParseResult,
  AiTaskReport,
  ResultSection,
  TaskResultAnswer,
  TaskResultAnswerState,
  TaskResultEvidence,
  TaskResultQuestion,
} from '@/types/ai'

export function parseAiQueryApi(text: string) {
  return request.post<unknown, AiQueryParseResult>('/ai/query/parse', { text })
}

export function generateTaskReportApi(taskId: number | string, silentError = false) {
  return request.post<unknown, AiTaskReport>(`/ai/reports/from-task/${taskId}`, undefined, { silentError })
}

export function getTaskResultAnswerApi(taskId: string, signal: AbortSignal, version?: string, beforeTurn?: number) {
  return request.get<unknown, TaskResultAnswerState>(`/agent/task-results/${taskId}/answers`,
    { params: { version, beforeTurn }, signal, silentError: true })
}

export function askTaskResultApi(taskId: string, question: TaskResultQuestion, signal: AbortSignal) {
  return request.post<unknown, TaskResultAnswer>(`/agent/task-results/${taskId}/answers`, question,
    { signal, silentError: true, timeout: 70000 })
}

export function getTaskResultEvidenceApi(taskId: string, version: string, section: ResultSection, signal: AbortSignal) {
  return request.get<unknown, TaskResultEvidence>(`/agent/task-results/${taskId}/answers/evidence`,
    { params: { version, section }, signal, silentError: true, timeout: 12000 })
}
