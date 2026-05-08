package com.remotesensing.platform.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class RsImageCreateDTO {

    @NotBlank(message = "影像编码不能为空")
    private String imageCode;

    @NotBlank(message = "影像名称不能为空")
    private String imageName;

    private String sensorType;

    private String satelliteName;

    private OffsetDateTime acquisitionTime;

    @DecimalMin(value = "0", message = "云量不能小于 0")
    @DecimalMax(value = "100", message = "云量不能大于 100")
    private BigDecimal cloudPercent;

    @DecimalMin(value = "0", inclusive = false, message = "分辨率必须大于 0")
    private BigDecimal resolutionMeter;

    @Min(value = 1, message = "波段数必须大于 0")
    private Integer bandCount;

    private String projection;

    @Min(value = 1, message = "影像宽度必须大于 0")
    private Integer width;

    @Min(value = 1, message = "影像高度必须大于 0")
    private Integer height;

    private String fileFormat = "GeoTIFF";

    @Min(value = 0, message = "文件大小不能小于 0")
    private Long fileSize;

    @NotBlank(message = "MinIO bucket 不能为空")
    private String minioBucket;

    @NotBlank(message = "对象存储路径不能为空")
    private String objectKey;

    private String overviewObjectKey;

    @NotBlank(message = "影像空间范围不能为空")
    private String footprintWkt;

    private BigDecimal centerLon;

    private BigDecimal centerLat;

    private String description;
}
