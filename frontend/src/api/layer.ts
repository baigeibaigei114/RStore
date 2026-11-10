import request, { apiEndpoint } from '@/api/request'
import type { PageResult } from '@/types/api'
import type { LayerListItem, LayerSearchParams } from '@/types/layer'

/**
 * 分页查询已发布图层列表接口
 * 支持按任务类型、影像 ID、关键字搜索
 * @param params - 搜索条件，包含 pageNum/pageSize 分页参数及可选的 taskType/imageId/keyword
 * @returns Promise<PageResult<LayerListItem>> - 分页的图层列表
 */
export function listLayersApi(params: LayerSearchParams) {
  return request.get<unknown, PageResult<LayerListItem>>('/layers', { params })
}

/**
 * 将后端返回的代理路径解析到当前配置的 API 地址，兼容本地 /api 路径。
 * URL 为空时返回空字符串。
 * @param url - 原始代理 URL
 * @returns 规范化后的 URL 或空字符串
 */
export function normalizeProxyUrl(url?: string) {
  return apiEndpoint(url)
}

/**
 * 获取图层的 WMS 代理地址
 * 优先使用图层自身的 proxyWmsUrl，若不存在则构造默认代理路径 /api/layers/{id}/wms
 * @param layer - 图层对象，至少包含 id 和 proxyWmsUrl 字段
 * @returns WMS 代理 URL 字符串
 */
export function getLayerProxyWmsUrl(layer: Pick<LayerListItem, 'id' | 'proxyWmsUrl'>) {
  return normalizeProxyUrl(layer.proxyWmsUrl) || apiEndpoint(`/layers/${layer.id}/wms`)
}

/**
 * 获取图层的 WCS 代理地址
 * 优先使用图层自身的 proxyWcsUrl，若不存在则构造默认代理路径 /api/layers/{id}/wcs
 * @param layer - 图层对象，至少包含 id 和 proxyWcsUrl 字段
 * @returns WCS 代理 URL 字符串
 */
export function getLayerProxyWcsUrl(layer: Pick<LayerListItem, 'id' | 'proxyWcsUrl'>) {
  return normalizeProxyUrl(layer.proxyWcsUrl) || apiEndpoint(`/layers/${layer.id}/wcs`)
}
