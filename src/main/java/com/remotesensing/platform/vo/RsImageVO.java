package com.remotesensing.platform.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class RsImageVO {

    private Long id;
    private String imageCode;
    private String imageName;
    private String sensorType;
    private String satelliteName;
    private OffsetDateTime acquisitionTime;
    private BigDecimal cloudPercent;
    private BigDecimal resolutionMeter;
    private Integer bandCount;
    private String projection;
    private Integer width;
    private Integer height;
    private String fileFormat;
    private Long fileSize;
    private String contentType;
    private String metadataJson;
    private String minioBucket;
    private String objectKey;
    private String thumbnailObjectKey;
    private String thumbnailStatus;
    private String thumbnailErrorMessage;
    private String overviewObjectKey;
    private String footprintWkt;
    private BigDecimal centerLon;
    private BigDecimal centerLat;
    private String status;
    private String description;
    private OffsetDateTime deletedAt;
    private String deletedReason;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
