package com.remotesensing.platform.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class RsImageSearchDTO {

    private Long regionId;
    private String keyword;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private String sensor;
    private BigDecimal maxCloudPercent;
    private BigDecimal minLng;
    private BigDecimal minLat;
    private BigDecimal maxLng;
    private BigDecimal maxLat;
    private boolean hasBbox;
}
