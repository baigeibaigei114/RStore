package com.remotesensing.platform.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 影像资产实体，对应 rs_image 表。
 * footprint 数据库类型是 geometry，Java 侧使用 WKT 字符串承接。
 */
@Data
public class RsImage {

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
    private String deletedBy;
    private String deletedReason;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
