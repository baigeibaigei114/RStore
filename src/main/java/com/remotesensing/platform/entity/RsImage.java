package com.remotesensing.platform.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 影像资产实体，对应 rs_image 表。
 * <p>
 * 存储遥感影像的元数据、存储位置、空间范围、缩略图及生命周期状态。
 * footprint 数据库类型是 geometry，Java 侧使用 WKT 字符串承接。
 */
@Data
public class RsImage {

    /** 主键 ID。 */
    private Long id;

    /** 所有者用户 ID。 */
    private String ownerId;

    /** 可见性：PRIVATE / PUBLIC，控制其他用户能否检索到该影像。 */
    private String visibility;

    /** 影像业务编码，全局唯一。 */
    private String imageCode;

    /** 影像名称，用户自定义。 */
    private String imageName;

    /** 传感器类型，如 Optical、SAR 等。 */
    private String sensorType;

    /** 卫星名称，如 Sentinel-2、Landsat 8 等。 */
    private String satelliteName;

    /** 影像采集时间。 */
    private OffsetDateTime acquisitionTime;

    /** 云量百分比（0~100）。 */
    private BigDecimal cloudPercent;

    /** 空间分辨率（米）。 */
    private BigDecimal resolutionMeter;

    /** 波段数量。 */
    private Integer bandCount;

    /** 投影坐标系（EPSG 编码，如 EPSG:4326）。 */
    private String projection;

    /** 影像宽度（像素）。 */
    private Integer width;

    /** 影像高度（像素）。 */
    private Integer height;

    /** 文件格式，默认 GeoTIFF。 */
    private String fileFormat;

    /** 文件大小（字节）。 */
    private Long fileSize;

    /** 文件 MIME 类型。 */
    private String contentType;

    /** 元数据 JSON（Python 脚本解析 GeoTIFF 后的完整元数据）。 */
    private String metadataJson;

    /** MinIO 存储桶名称。 */
    private String minioBucket;

    /** MinIO 对象键（原始 GeoTIFF 文件的存储路径）。 */
    private String objectKey;

    /** 缩略图对象键（生成的预览图在 MinIO 中的路径）。 */
    private String thumbnailObjectKey;

    /** 缩略图状态：PENDING / RUNNING / SUCCESS / FAILED / SKIPPED。 */
    private String thumbnailStatus;

    /** 缩略图生成失败时的错误信息。 */
    private String thumbnailErrorMessage;

    /** 概览图对象键（金字塔缩略图，用于地图预览）。 */
    private String overviewObjectKey;

    /** 影像空间范围 WKT 字符串（数据库 geometry 类型）。 */
    private String footprintWkt;

    /** 中心点经度。 */
    private BigDecimal centerLon;

    /** 中心点纬度。 */
    private BigDecimal centerLat;

    /** 影像状态：UPLOADING / PARSING / READY / PROCESSING / DELETE_LOCKED / DELETED / FAILED。 */
    private String status;

    /** 影像描述信息。 */
    private String description;

    /** 软删除时间。 */
    private OffsetDateTime deletedAt;

    /** 删除操作的用户 ID。 */
    private String deletedBy;

    /** 删除原因。 */
    private String deletedReason;

    /** 创建时间。 */
    private OffsetDateTime createdAt;

    /** 最后更新时间。 */
    private OffsetDateTime updatedAt;
}
