import type { PageResult } from '@/types/api'
import type { TaskType } from '@/types/task'

export type AiPlanStatus = 'DRAFT' | 'VALID' | 'INVALID' | 'CONFIRMED' | 'CANCELED'
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

export interface AiPlanStep {
  order?: number
  type?: string
  description?: string
  params?: Record<string, unknown>
  requiresConfirmation?: boolean
}

export interface AiPlanContent {
  goal?: string
  steps?: AiPlanStep[]
}

export interface AiPlan {
  id: number
  status: AiPlanStatus | string
  title?: string
  userInput?: string
  plan?: AiPlanContent
  validationErrors?: string[]
  createdAt?: string
  updatedAt?: string
}

export interface AiPlanListItem {
  id: number
  status: AiPlanStatus | string
  title?: string
  userInput?: string
  createdAt?: string
  updatedAt?: string
}

export interface AiPlanListParams {
  pageNum?: number
  pageSize?: number
  status?: string
}

export type AiPlanPageResult = PageResult<AiPlanListItem>
