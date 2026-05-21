/** 行政区划基本节点信息 */
export interface AdminRegion {
  /** 行政区唯一 ID */
  id: number
  /** 行政区名称 */
  name: string
  /** 行政级别，如 province（省）/ city（市）/ district（区县） */
  level: string
  /** 父级行政区 ID，顶级节点为 null */
  parentId: number | null
}

/** 行政区划详情（包含边界 GeoJSON 数据） */
export interface AdminRegionDetail extends AdminRegion {
  /** 行政区边界的 GeoJSON 字符串，用于在地图上绘制边界 */
  boundaryGeoJson?: string
}
