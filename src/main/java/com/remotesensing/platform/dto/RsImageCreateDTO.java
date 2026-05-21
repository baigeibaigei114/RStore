package com.remotesensing.platform.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 遥感影像创建请求 DTO。
 * 用于接收前端上传影像元数据时的请求体，包含影像基本属性、空间参考信息及 MinIO 存储路径。
 */
@Data
public class RsImageCreateDTO {

    /** 影像唯一编码，业务标识符，对应 rs_image 表 image_code 列。不能为空。 */
    @NotBlank(message = "影像编码不能为空")
    private String imageCode;

    /** 影像名称，用于界面展示，对应 rs_image 表 image_name 列。不能为空。 */
    @NotBlank(message = "影像名称不能为空")
    private String imageName;

    /** 传感器类型，如 "MSI"、"OLI"、"SAR" 等，对应 rs_image 表 sensor_type 列。 */
    private String sensorType;

    /** 卫星平台名称，如 "Sentinel-2"、"Landsat-9" 等，对应 rs_image 表 satellite_name 列。 */
    private String satelliteName;

    /** 影像采集时间（UTC），对应 rs_image 表 acquisition_time 列。 */
    private OffsetDateTime acquisitionTime;

    /** 云量百分比，取值范围 0-100，对应 rs_image 表 cloud_percent 列。 */
    @DecimalMin(value = "0", message = "云量不能小于 0")
    @DecimalMax(value = "100", message = "云量不能大于 100")
    private BigDecimal cloudPercent;

    /** 影像空间分辨率（米），必须大于 0，对应 rs_image 表 resolution_meter 列。 */
    @DecimalMin(value = "0", inclusive = false, message = "分辨率必须大于 0")
    private BigDecimal resolutionMeter;

    /** 波段数量，必须大于 0，对应 rs_image 表 band_count 列。 */
    @Min(value = 1, message = "波段数必须大于 0")
    private Integer bandCount;

    /** 投影坐标系（CRS），如 "EPSG:4326"、"EPSG:32650"，对应 rs_image 表 projection 列。 */
    private String projection;

    /** 影像像素宽度，必须大于 0，对应 rs_image 表 width 列。 */
    @Min(value = 1, message = "影像宽度必须大于 0")
    private Integer width;

    /** 影像像素高度，必须大于 0，对应 rs_image 表 height 列。 */
    @Min(value = 1, message = "影像高度必须大于 0")
    private Integer height;

    /** 文件格式，默认 "GeoTIFF"，对应 rs_image 表 file_format 列。 */
    private String fileFormat = "GeoTIFF";

    /** 文件大小（字节），不能小于 0，对应 rs_image 表 file_size 列。 */
    @Min(value = 0, message = "文件大小不能小于 0")
    private Long fileSize;

    /** 存储桶名称，对应 rs_image 表 minio_bucket 列。不能为空。 */
    @NotBlank(message = "MinIO bucket 不能为空")
    private String minioBucket;

    /** 原始文件在 MinIO 中的对象键（objectKey），对应 rs_image 表 object_key 列。不能为空。 */
    @NotBlank(message = "对象存储路径不能为空")
    private String objectKey;

    /** 概览文件（金字塔/缩略图）在 MinIO 中的对象键，对应 rs_image 表 overview_object_key 列。 */
    private String overviewObjectKey;

    /** 影像空间覆盖范围（WKT 格式），对应 rs_image 表 footprint_wkt 列。不能为空。 */
    @NotBlank(message = "影像空间范围不能为空")
    private String footprintWkt;

    /** 中心点经度（WGS84），对应 rs_image 表 center_lon 列。 */
    private BigDecimal centerLon;

    /** 中心点纬度（WGS84），对应 rs_image 表 center_lat 列。 */
    private BigDecimal centerLat;

    /** 影像描述信息，对应 rs_image 表 description 列。 */
    private String description;
}
