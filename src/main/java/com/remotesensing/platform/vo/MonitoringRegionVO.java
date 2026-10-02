package com.remotesensing.platform.vo;

import lombok.Data;

@Data
public class MonitoringRegionVO {
    private Long id;
    private String name;
    /** 完整精度 GeoJSON，不用于简化展示后再提交计算。 */
    private String geometryJson;
    private Integer version;
}
