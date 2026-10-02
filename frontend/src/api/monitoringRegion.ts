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

export function listMonitoringRegionsApi() {
  return request.get<unknown, MonitoringRegion[]>('/monitoring-regions')
}

export function getMonitoringRegionApi(id: number) {
  return request.get<unknown, MonitoringRegion>(`/monitoring-regions/${id}`)
}

export function saveMonitoringRegionApi(name: string, geometry: RegionPolygon, id?: number) {
  const payload = { name, geometry }
  return id
    ? request.put<unknown, MonitoringRegion>(`/monitoring-regions/${id}`, payload)
    : request.post<unknown, MonitoringRegion>('/monitoring-regions', payload)
}
