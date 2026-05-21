package com.remotesensing.platform.vo;

import lombok.Data;

/**
 * 行政区划简单 VO。
 * 用于展示行政区划列表或树形结构的基础信息，不包含空间边界数据。
 * 对应 admin_region 表的核心字段。
 */
@Data
public class AdminRegionVO {

    /** 行政区划主键 ID，对应 admin_region.id。 */
    private Long id;

    /** 行政区划代码（如 "110000" 表示北京市），对应 admin_region.adcode。 */
    private String adcode;

    /** 行政区划名称（如 "北京市"、"海淀区"），对应 admin_region.name。 */
    private String name;

    /** 行政区划级别：province（省）/ city（市）/ district（区县），对应 admin_region.level。 */
    private String level;

    /** 父级行政区划主键 ID，对应 admin_region.parent_id。顶级节点该值为 NULL。 */
    private Long parentId;
}
