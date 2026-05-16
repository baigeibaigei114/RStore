package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class RsResultFile {

    private Long id;
    private String ownerId;
    private String visibility;
    private Long taskId;
    private Long imageId;
    private String fileName;
    private String fileType;
    private String minioBucket;
    private String objectKey;
    private Long fileSize;
    private String mimeType;
    private String checksum;
    private String resultMetadata;
    private String status;
    private String workspace;
    private String storeName;
    private String layerName;
    private String wmsUrl;
    private String wcsUrl;
    private String publishErrorMessage;
    private OffsetDateTime publishedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
