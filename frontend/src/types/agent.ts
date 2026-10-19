export type AgentStatus = 'RUNNING' | 'NEEDS_INPUT' | 'WAITING_CONFIRMATION' | 'EXPIRED' | 'WAITING_TASK'
  | 'READY_TO_EXPLAIN' | 'EXPLAINING' | 'SUCCEEDED' | 'INSUFFICIENT_EVIDENCE' | 'FAILED' | 'UNSUPPORTED' | 'CANCELED'

export interface ImageCandidate { id: string; name: string; sensor?: string | null; captureTime?: string | null; admission: string }
export interface RegionCandidate { id: string; name: string; version: number }
export interface AgentDraft {
  draftVersion: number; draftHash: string; expiresAt: string; imageId: string; imageName: string; captureTime?: string | null
  monitoringRegionId: string; regionName: string; regionVersion: number; taskType: 'NDVI'
  params: { redBand: number; nirBand: number }; operationLabel: string
}
export interface AgentCitation {
  citationId: string; sourceId: string; title: string; sourceKind: 'PLATFORM' | 'EXTERNAL_SCIENTIFIC'
  heading: string; headingAnchor: string; chunkId: string; documentSha256: string; chunkSha256: string
  knowledgeIndexId: string; excerpt: string; sourceUrl?: string | null
}
export interface AgentClaim { text: string; metricRefs: string[]; citationIds: string[]; evidenceQuotes: { citationId: string; quote: string }[] }
export interface AgentAnswer {
  requestId?: string; knowledgeIndexId: string; answer?: string; summary?: string; claims: AgentClaim[]
  insufficientEvidence: boolean; citations: AgentCitation[]
}
export interface AgentStatistics {
  mean: number; min: number; max: number; totalPixelCount: number; validPixelCount: number
  invalidPixelCount: number; validPixelRatio: number
}
export interface AgentExplanation extends AgentAnswer {
  taskId: string; resultMetadataSHA: string; statistics: AgentStatistics; coverageRatio: number
  regionVersion: number; bandMapping: { redBand: number; nirBand: number }; platformLimitations: string[]
}
export interface AgentExecution {
  requestId?: string; executionId: string; version: number; status: AgentStatus; question: string | null
  unsupportedReason: string | null; missingFields: string[]; imageCandidates: ImageCandidate[]; regionCandidates: RegionCandidate[]
  hasMoreCandidates: boolean; operation: { operationId: string; kind: string; deadline: string } | null
  draft: AgentDraft | null; task: { taskId: string; status: string; progress: number } | null
  explanation: AgentExplanation | null; error: { errorCode: string; message: string; retryable: boolean } | null
  budget: { modelCalls: number; toolCalls: number }
}
export interface AgentStep {
  sequence: number; kind: string; name: string; status: string; operationId: string; errorCode: string | null
  modelRequested: string | null; modelReturned: string | null; usage: Record<string, unknown> | null
  input: Record<string, unknown> | null; output: Record<string, unknown> | null
  startedAt: string; endedAt: string | null; durationMs?: number
}
export interface CreateExecution { clientRequestId: string; text: string }
export interface MessageExecution {
  clientRequestId: string; expectedVersion: number; text?: string; selectedImageId?: string; selectedRegionId?: string
}
export type PendingAction =
  | { kind: 'create'; body: CreateExecution }
  | { kind: 'message'; executionId: string; body: MessageExecution }
  | { kind: 'confirm'; executionId: string; body: { draftVersion: number; draftHash: string } }
  | { kind: 'explanation'; executionId: string; body: { clientRequestId: string } }
  | { kind: 'retry'; executionId: string; body: { clientRequestId: string; expectedVersion: number } }
  | { kind: 'cancel'; executionId: string; body: { expectedVersion: number } }
