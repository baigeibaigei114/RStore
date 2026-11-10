import type { RegionPolygon } from '@/api/monitoringRegion'

export type ProductStatus = 'AWAITING_UPLOAD' | 'UPLOADING' | 'INSPECT_QUEUED' | 'INSPECTING' | 'READY' | 'FAILED' | 'EXPIRED'
export interface IngestionError { reason: string; stage: string; retryable: boolean; message: string }
export interface SourceGrid { crs: string; width: number; height: number; transform: number[]; bounds?: number[] }
export interface ProductView {
  productId: string; name: string; status: ProductStatus; rawState: string; version: number; sizeBytes: number
  sourceId: string | null; captureTime: string | null; expiresAt: string | null
  descriptorVersion: number | null; profileVersion: string | null; grid: SourceGrid | null
  previewGrid?: SourceGrid; footprint: RegionPolygon | null; previewPath: string | null
  error: IngestionError | null; allowedActions: string[]
}
export interface ProductPage { items: ProductView[]; page: number; pageSize: number; total: number }
export interface CreateProduct { clientRequestId: string; name: string; fileName: string; sizeBytes: number }
export interface CreatedProduct { productId: string; status: ProductStatus; version: number; uploadPath: string; uploadExpiresAt: string; limits: { archiveMaxBytes: number } }
export interface PreflightRequest { clientRequestId: string; productId: string; monitoringRegionId: number; expectedRegionVersion: number; imageName: string }
export interface RetryRequest { clientRequestId: string; expectedVersion: number }
export interface ConfirmationView {
  confirmationId: string; status: 'PENDING' | 'READY' | 'CONSUMED' | 'FAILED' | 'EXPIRED'; version: number; productId: string
  regionSnapshot: { regionId: number; name: string; version: number; geometry: RegionPolygon }
  imageName: string; sourceId: string | null; profileVersion: string | null; sourceGrid: SourceGrid | null
  window: number[] | null; confirmationHash: string | null; expiresAt: string | null; error: IngestionError | null; jobId: string | null
}
export interface ConfirmRequest { clientRequestId: string; confirmationHash: string }
export type JobStatus = 'QUEUED' | 'RUNNING' | 'VALIDATING' | 'SUCCEEDED' | 'FAILED'
export interface JobSubmission {
  jobId: string; imageId: number | null; publicationId: string | null; reused: boolean; status: JobStatus
  overallStatus: 'STANDARDIZING' | 'FAILED' | 'INGESTED_PUBLISH_PENDING' | 'PUBLISH_FAILED' | 'COMPLETE'
}
export interface JobView extends Omit<JobSubmission, 'reused'> {
  assetStatus: 'AVAILABLE' | 'UNAVAILABLE' | 'CHANGED' | null
  stage: string; version: number; generation: number; productId: string
  regionSnapshot: ConfirmationView['regionSnapshot']; window: number[]
  publicationStatus: 'PENDING' | 'PUBLISHING' | 'AVAILABLE' | 'FAILED' | null
  error: IngestionError | null; allowedActions: string[]
  regionPixelCount: number | null; regionValidReflectanceCount: number | null
}
export interface JobRetryRequest extends RetryRequest { productId: string }
export interface JobPage { items: JobView[]; page: number; pageSize: number; total: number }
export type PublicationStatus = 'PENDING' | 'PUBLISHING' | 'AVAILABLE' | 'FAILED'
export interface PublicationView {
  publicationId: string; imageId: number; status: PublicationStatus; version: number; generation: number
  error: IngestionError | null; layerId: string | null; wmsPath: string | null; wcsPath: string | null
}
export interface StandardizedLayer {
  sourceKind: 'STANDARDIZED_IMAGE'; layerId: string; imageId: number; name: string
  bounds: { left: number; bottom: number; right: number; top: number }; wmsPath: string; wcsPath: string
}
export interface StandardizedLayerPage { items: StandardizedLayer[]; page: number; pageSize: number; total: number }
export interface AssetMapRequest { bbox: string; srs: 'EPSG:3857'; width: number; height: number; transparent: boolean }
