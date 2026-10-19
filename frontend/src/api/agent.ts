import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { AgentAnswer, AgentExecution, AgentStep, PendingAction } from '@/types/agent'

// Writes can use the bounded model round; reads never cause a model call.
export function executeAgentAction(action: PendingAction, signal?: AbortSignal) {
  const config = { timeout: 40000, silentError: true, signal }
  switch (action.kind) {
    case 'create': return request.post<unknown, AgentExecution>('/agent/executions', action.body, config)
    case 'message': return request.post<unknown, AgentExecution>(`/agent/executions/${action.executionId}/messages`, action.body, config)
    case 'confirm': return request.post<unknown, AgentExecution>(`/agent/executions/${action.executionId}/confirm`, action.body, config)
    case 'explanation': return request.post<unknown, AgentExecution>(`/agent/executions/${action.executionId}/explanation`, action.body, config)
    case 'retry': return request.post<unknown, AgentExecution>(`/agent/executions/${action.executionId}/retry`, action.body, config)
    case 'cancel': return request.post<unknown, AgentExecution>(`/agent/executions/${action.executionId}/cancel`, action.body, config)
  }
}
export function getAgentExecution(id: string, signal?: AbortSignal) {
  return request.get<unknown, AgentExecution>(`/agent/executions/${id}`, { timeout: 10000, silentError: true, signal })
}
export function listAgentExecutions(pageNum = 1, signal?: AbortSignal) {
  return request.get<unknown, PageResult<AgentExecution>>('/agent/executions', { params: { pageNum, pageSize: 10 }, silentError: true, signal })
}
export function getAgentSteps(id: string, signal?: AbortSignal) {
  return request.get<unknown, { requestId: string; steps: AgentStep[] }>(`/agent/executions/${id}/steps`, { silentError: true, signal })
}
export function answerAgentQuestion(question: string, signal?: AbortSignal) {
  return request.post<unknown, AgentAnswer>('/agent/answers', { question }, { timeout: 40000, silentError: true, signal })
}
