import request from '@/api/request'

export interface MapAsset {
  layerId: string
  name: string
  bounds: { left: number; bottom: number; right: number; top: number }
}

// This read-only boundary is available independently of the optional SAFE UI.
export function listMapAssets(page: number) {
  return request.get<unknown, { items: MapAsset[]; page: number; total: number }>('/ingestion/layers', {
    params: { page, pageSize: 20 }, silentError: true,
  })
}
