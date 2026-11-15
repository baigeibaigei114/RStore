import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { AgentAnswer, AgentExecution, AgentStep, PendingAction } from '@/types/agent'

interface PreparationImage { id: number; name: string; captureDate: string | null }
interface PreparationRegion { id: number; name: string; version: number }
export interface AnalysisDraft extends PreparationImage {
  regionId: number
  regionName: string
  regionVersion: number
  imageIdentity: string
  coverage: 'FULL' | 'PARTIAL' | 'OUTSIDE' | 'UNKNOWN'
  bands: { redBand: number; nirBand: number }
}
interface Preparation {
  status: 'READY' | 'CHOOSE' | 'NO_MATCH' | 'UNSUPPORTED'
  modelCalls: number
  modelRoundMs: number
  images?: PreparationImage[]
  regions?: PreparationRegion[]
  moreImages?: boolean
  moreRegions?: boolean
  draft?: AnalysisDraft
}

const assistant = '/agent/imaging-assistant'
export function prepareAnalysisApi(goal: string, signal?: AbortSignal) {
  return request.post<unknown, Preparation>(`${assistant}/preparation`, { goal }, { timeout: 40000, silentError: true, signal })
}
export function readAnalysisDraftApi(imageId: number, monitoringRegionId: number, signal?: AbortSignal) {
  return request.post<unknown, AnalysisDraft>(`${assistant}/draft`,
    { imageId: String(imageId), monitoringRegionId: String(monitoringRegionId) }, { silentError: true, signal })
}
export function askPlatformKnowledgeApi(question: string, signal?: AbortSignal) {
  return request.post<unknown, AgentAnswer>(`${assistant}/knowledge`, { question }, { timeout: 40000, silentError: true, signal })
}

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
