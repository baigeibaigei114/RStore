package com.remotesensing.platform.vo;

import lombok.Data;

/**
 * 行政区划详情 VO。
 * 在基础信息基础上额外包含空间边界 GeoJSON 数据，用于前端在地图上绘制行政区边界。
 */
@Data
public class AdminRegionDetailVO {

    /** 行政区划主键 ID，对应 admin_region.id。 */
    private Long id;

    /** 行政区划代码（如 "110000" 表示北京市），对应 admin_region.adcode。 */
    private String adcode;

    /** 行政区划名称（如 "北京市"、"海淀区"），对应 admin_region.name。 */
    private String name;

    /** 行政区划级别：province（省）/ city（市）/ district（区县），对应 admin_region.level。 */
    private String level;

    /** 父级行政区划主键 ID，对应 admin_region.parent_id。 */
    private Long parentId;

    /** 行政区边界 GeoJSON 字符串，从 admin_region.boundary 空间字段通过 ST_AsGeoJSON 转换得到。 */
    private String boundaryGeoJson;
}
