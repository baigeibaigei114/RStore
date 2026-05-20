import request from '@/api/request'
import type { PageResult } from '@/types/api'
import type { LayerListItem, LayerSearchParams } from '@/types/layer'

export function listLayersApi(params: LayerSearchParams) {
  return request.get<unknown, PageResult<LayerListItem>>('/layers', { params })
}

export function normalizeProxyUrl(url?: string) {
  if (!url) {
    return ''
  }

  return url.startsWith('/api/') ? url : `/api${url.startsWith('/') ? url : `/${url}`}`
}

export function getLayerProxyWmsUrl(layer: Pick<LayerListItem, 'id' | 'proxyWmsUrl'>) {
  return normalizeProxyUrl(layer.proxyWmsUrl) || `/api/layers/${layer.id}/wms`
}

export function getLayerProxyWcsUrl(layer: Pick<LayerListItem, 'id' | 'proxyWcsUrl'>) {
  return normalizeProxyUrl(layer.proxyWcsUrl) || `/api/layers/${layer.id}/wcs`
}
