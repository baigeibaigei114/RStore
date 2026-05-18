export interface AdminRegion {
  id: number
  name: string
  level: string
  parentId: number | null
}

export interface AdminRegionDetail extends AdminRegion {
  boundaryGeoJson?: string
}
