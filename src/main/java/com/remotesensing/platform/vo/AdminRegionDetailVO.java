package com.remotesensing.platform.vo;

import lombok.Data;

@Data
public class AdminRegionDetailVO {

    private Long id;
    private String name;
    private String level;
    private Long parentId;
    private String boundaryGeoJson;
}
