import type { RegionPolygon } from '@/api/monitoringRegion'
import type { ConfirmRequest, CreateProduct, JobRetryRequest, PreflightRequest, RetryRequest } from '@/types/ingestion'

export interface IngestionDraft {
  productId?: string; confirmationId?: string; selectedRegionId?: number; imageName?: string
  productName?: string
  regionName?: string; geometry?: RegionPolygon; uploadUnknown?: boolean
  regionSaveUnknown?: boolean
  create?: CreateProduct; preflight?: PreflightRequest; retry?: { productId: string; body: RetryRequest }
  jobId?: string; confirm?: { confirmationId: string; body: ConfirmRequest }; jobRetry?: { jobId: string; body: JobRetryRequest }
}
const key = (owner: string) => `rs_ingestion_v1:${encodeURIComponent(owner)}`
export function loadIngestionDraft(owner: string): IngestionDraft {
  try {
    const value = JSON.parse(sessionStorage.getItem(key(owner)) || '{}')
    return value && typeof value === 'object' && !Array.isArray(value) ? value as IngestionDraft : {}
  } catch { return {} }
}
export function saveIngestionDraft(owner: string, draft: IngestionDraft) {
  // IDs, user inputs and idempotent request bodies only; never persist file bytes or credentials.
  try { sessionStorage.setItem(key(owner), JSON.stringify(draft)) } catch { /* Current inputs remain available even if storage is full. */ }
}
