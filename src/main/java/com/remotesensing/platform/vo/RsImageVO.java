package com.remotesensing.platform.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 遥感影像详情 VO。
 * 用于展示单条影像的完整元数据，包含影像属性、存储路径、缩略图信息、空间范围以及软删除状态。
 * 对应 rs_image 表的全部字段。
 */
@Data
public class RsImageVO {

    /** 影像主键 ID，对应 rs_image.id。 */
    private Long id;

    /** 影像所属用户 ID，对应 rs_image.owner_id。 */
    private String ownerId;

    /** 可见性：PUBLIC（公开）/ PRIVATE（私有），对应 rs_image.visibility。 */
    private String visibility;

    /** 影像唯一业务编码，对应 rs_image.image_code。 */
    private String imageCode;

    /** 影像名称，对应 rs_image.image_name。 */
    private String imageName;

    /** 传感器类型，如 "MSI"、"OLI"，对应 rs_image.sensor_type。 */
    private String sensorType;

    /** 卫星平台名称，如 "Sentinel-2"、"Landsat-9"，对应 rs_image.satellite_name。 */
    private String satelliteName;

    /** 影像采集时间（UTC），对应 rs_image.acquisition_time。 */
    private OffsetDateTime acquisitionTime;

    /** 云量百分比（0-100），对应 rs_image.cloud_percent。 */
    private BigDecimal cloudPercent;

    /** 空间分辨率（米），对应 rs_image.resolution_meter。 */
    private BigDecimal resolutionMeter;

    /** 波段数量，对应 rs_image.band_count。 */
    private Integer bandCount;

    /** 投影坐标系（CRS），如 "EPSG:4326"，对应 rs_image.projection。 */
    private String projection;

    /** 影像像素宽度，对应 rs_image.width。 */
    private Integer width;

    /** 影像像素高度，对应 rs_image.height。 */
    private Integer height;

    /** 文件格式，如 "GeoTIFF"，对应 rs_image.file_format。 */
    private String fileFormat;

    /** 文件大小（字节），对应 rs_image.file_size。 */
    private Long fileSize;

    /** 文件 MIME 类型，对应 rs_image.content_type。 */
    private String contentType;

    /** 影像元数据 JSON 字符串，对应 rs_image.metadata_json。 */
    private String metadataJson;

    /** 原始文件所在 MinIO 存储桶，对应 rs_image.minio_bucket。 */
    private String minioBucket;

    /** 原始文件在 MinIO 中的对象键，对应 rs_image.object_key。 */
    private String objectKey;

    /** 缩略图文件在 MinIO 中的对象键，对应 rs_image.thumbnail_object_key。 */
    private String thumbnailObjectKey;

    /** 缩略图生成状态：PENDING / GENERATING / COMPLETED / FAILED，对应 rs_image.thumbnail_status。 */
    private String thumbnailStatus;

    /** 缩略图生成失败时的错误信息，对应 rs_image.thumbnail_error_message。 */
    private String thumbnailErrorMessage;

    /** 概览文件（金字塔）在 MinIO 中的对象键，对应 rs_image.overview_object_key。 */
    private String overviewObjectKey;

    /** 影像覆盖范围（WKT 格式），对应 rs_image.footprint_wkt。 */
    private String footprintWkt;

    /** 中心点经度（WGS84），对应 rs_image.center_lon。 */
    private BigDecimal centerLon;

    /** 中心点纬度（WGS84），对应 rs_image.center_lat。 */
    private BigDecimal centerLat;

    /** 影像处理状态：READY / PROCESSING，对应 rs_image.status。 */
    private String status;

    /** 影像描述信息，对应 rs_image.description。 */
    private String description;

    /** 软删除时间（UTC），非空表示已删除，对应 rs_image.deleted_at。 */
    private OffsetDateTime deletedAt;

    /** 软删除原因，对应 rs_image.deleted_reason。 */
    private String deletedReason;

    /** 记录创建时间（UTC），对应 rs_image.created_at。 */
    private OffsetDateTime createdAt;

    /** 记录更新时间（UTC），对应 rs_image.updated_at。 */
    private OffsetDateTime updatedAt;
}
