package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 行政区划实体，对应 admin_region 表。
 * <p>
 * 存储中国行政区划数据（省/市/区/街道四级），包含行政编码、名称、层级关系
 * 以及空间边界 GeoJSON，用于影像检索时的行政区域筛选和空间查询。
 */
@Data
public class AdminRegion {

    /** 主键 ID。 */
    private Long id;

    /** 行政区划代码（如 110000 表示北京市）。 */
    private String adcode;

    /** 行政区划名称。 */
    private String name;

    /** 行政级别：province / city / district / street。 */
    private String level;

    /** 父级行政区划 ID。 */
    private Long parentId;

    /** 行政区划边界 GeoJSON 字符串（空间范围）。 */
    private String boundaryGeoJson;

    /** 创建时间。 */
    private OffsetDateTime createdAt;

    /** 最后更新时间。 */
    private OffsetDateTime updatedAt;
}
