import type { TaskType } from '@/types/task'

export type AiRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'UNKNOWN'

export interface AiQueryParseResult {
  regionName?: string
  startTime?: string
  endTime?: string
  sensor?: string
  maxCloudPercent?: number
  taskTypes?: TaskType[]
}

export interface AiReportJson {
  keyFindings?: string[]
  riskLevel?: AiRiskLevel | string
  suggestions?: string[]
  [key: string]: unknown
}

export interface AiTaskReport {
  id: number
  taskId: number
  imageId?: number
  ownerId?: string
  reportType?: string
  summary?: string
  reportJson?: AiReportJson
  createdAt?: string
  updatedAt?: string
}

export interface TaskResultAnswer {
  requestId: string
  resultVersion: string
  number: number
  question: string
  status: 'RUNNING' | 'COMPLETE' | 'FAILED'
  error: string | null
  answer: {
    kind: string
    statement: string
    regionVersion: number
    evidence: { key: string; label: string; value: number; unit: 'decimal' | 'count' | 'ratio' }[]
  } | null
}

export interface TaskResultAnswerState {
  resultVersion: string | null
  currentVersion: string | null
  canAsk: boolean
  message?: string
  turns: TaskResultAnswer[]
  hasEarlier: boolean
  historyVersions: string[]
}

export interface TaskResultQuestion {
  requestId: string
  resultVersion: string
  expectedTurn: number
  question: string
}
