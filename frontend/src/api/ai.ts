import request from '@/api/request'
import type {
  AiQueryParseResult,
  AiTaskReport,
  ResultSection,
  TaskResultAnswerState,
  TaskResultEvidence,
} from '@/types/ai'

export function parseAiQueryApi(text: string) {
  return request.post<unknown, AiQueryParseResult>('/ai/query/parse', { text })
}

export function getSavedTaskReportApi(taskId: number | string, signal: AbortSignal) {
  return request.get<unknown, AiTaskReport | null>(`/ai/reports/from-task/${taskId}`, { signal, silentError: true })
}

export function getTaskResultAnswerApi(taskId: string, signal: AbortSignal, version?: string, beforeTurn?: number) {
  return request.get<unknown, TaskResultAnswerState>(`/agent/task-results/${taskId}/answers`,
    { params: { version, beforeTurn }, signal, silentError: true })
}

export function getTaskResultEvidenceApi(taskId: string, version: string, section: ResultSection, signal: AbortSignal) {
  return request.get<unknown, TaskResultEvidence>(`/agent/task-results/${taskId}/answers/evidence`,
    { params: { version, section }, signal, silentError: true, timeout: 12000 })
}
