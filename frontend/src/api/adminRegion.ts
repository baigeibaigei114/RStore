import request from '@/api/request'
import type { AdminRegion, AdminRegionDetail } from '@/types/adminRegion'

/**
 * 获取行政区子节点列表接口
 * 不传 parentId 时返回顶级行政区（省级）；传递 parentId 返回该区域的下一级子区域（市/区县）
 * @param parentId - 可选，父级行政区 ID，不传则获取顶级节点
 * @returns Promise<AdminRegion[]> - 行政区列表，每个元素包含 id、name、level、parentId
 */
export function getRegionChildren(parentId?: number) {
  return request.get<unknown, AdminRegion[]>('/admin-regions/children', {
    params: parentId ? { parentId } : undefined,
  })
}

/**
 * 获取行政区详情接口
 * 返回行政区的详细信息，包括名称、级别和边界 GeoJSON 数据
 * @param id - 行政区 ID
 * @returns Promise<AdminRegionDetail> - 行政区详情，包含可选的 boundaryGeoJson 边界数据
 */
export function getRegionDetail(id: number) {
  return request.get<unknown, AdminRegionDetail>(`/admin-regions/${id}`)
}
