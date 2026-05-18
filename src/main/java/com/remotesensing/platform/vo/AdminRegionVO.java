package com.remotesensing.platform.vo;

import lombok.Data;

@Data
public class AdminRegionVO {

    private Long id;
    private String name;
    private String level;
    private Long parentId;
}
