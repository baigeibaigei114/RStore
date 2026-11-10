import request from '@/api/request'
export interface RegionPolygon {
  type: 'Polygon'
  coordinates: number[][][]
}

export interface MonitoringRegion {
  id: number
  name: string
  geometryJson: string
  version: number
}

export function listMonitoringRegionsApi(silentError = false) {
  return request.get<unknown, MonitoringRegion[]>('/monitoring-regions', { silentError })
}

export function getMonitoringRegionApi(id: number, silentError = false) {
  return request.get<unknown, MonitoringRegion>(`/monitoring-regions/${id}`, { silentError })
}

export function saveMonitoringRegionApi(name: string, geometry: RegionPolygon, id?: number) {
  const payload = { name, geometry }
  return id
    ? request.put<unknown, MonitoringRegion>(`/monitoring-regions/${id}`, payload)
    : request.post<unknown, MonitoringRegion>('/monitoring-regions', payload)
}
