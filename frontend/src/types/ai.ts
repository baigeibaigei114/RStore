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
