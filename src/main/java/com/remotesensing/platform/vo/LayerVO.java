package com.remotesensing.platform.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class LayerVO {

    private Long id;
    private Long taskId;
    private Long imageId;
    private String imageName;
    private OffsetDateTime imageDeletedAt;
    private String taskType;
    private String taskName;
    private String fileName;
    private String fileType;
    private String ownerId;
    private String visibility;
    private String workspace;
    private String storeName;
    private String layerName;
    private String qualifiedLayerName;
    private String proxyWmsUrl;
    private String proxyWcsUrl;
    private OffsetDateTime publishedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
