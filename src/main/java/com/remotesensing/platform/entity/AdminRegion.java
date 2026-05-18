package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class AdminRegion {

    private Long id;
    private String name;
    private String level;
    private Long parentId;
    private String boundaryGeoJson;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
