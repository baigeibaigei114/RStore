package com.remotesensing.platform.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class RsImageListVO {

    private Long id;
    private String imageCode;
    private String imageName;
    private String sensorType;
    private OffsetDateTime acquisitionTime;
    private BigDecimal cloudPercent;
    private BigDecimal resolutionMeter;
    private Integer width;
    private Integer height;
    private String objectKey;
    private String thumbnailObjectKey;
    private String thumbnailStatus;
    private String status;
    private OffsetDateTime createdAt;
}
