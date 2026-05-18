import request from '@/api/request'
import type { AdminRegion, AdminRegionDetail } from '@/types/adminRegion'

export function getRegionChildren(parentId?: number) {
  return request.get<unknown, AdminRegion[]>('/admin-regions/children', {
    params: parentId ? { parentId } : undefined,
  })
}

export function getRegionDetail(id: number) {
  return request.get<unknown, AdminRegionDetail>(`/admin-regions/${id}`)
}
