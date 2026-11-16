package com.remotesensing.platform.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** 访客影像的展示字段。存储引用和用户信息不进入响应。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicImageVO(
        Long id, String imageName, String sensorType, OffsetDateTime acquisitionTime,
        BigDecimal cloudPercent, String status, Integer width, Integer height,
        BigDecimal resolutionMeter, String footprintWkt, String thumbnailStatus, Boolean previewAvailable,
        RsImageVO.ThumbnailPreview thumbnailPreview) {}
