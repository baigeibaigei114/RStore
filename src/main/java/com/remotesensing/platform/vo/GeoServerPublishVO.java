package com.remotesensing.platform.vo;

import lombok.Data;

@Data
public class GeoServerPublishVO {

    private Long taskId;
    private String ownerId;
    private String visibility;
    private String workspace;
    private String storeName;
    private String layerName;
    private String qualifiedLayerName;
    private String sourceObjectKey;
    private String wmsUrl;
    private String wcsUrl;
    private String layerPreviewUrl;
}
