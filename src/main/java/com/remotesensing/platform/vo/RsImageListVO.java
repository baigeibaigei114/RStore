package com.remotesensing.platform.vo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 遥感影像列表 VO。
 * 用于展示影像列表页的摘要信息，相较于 RsImageVO 省略了存储路径、元数据等内部细节。
 */
@Data
public class RsImageListVO {

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

    /** 影像采集时间（UTC），对应 rs_image.acquisition_time。 */
    private OffsetDateTime acquisitionTime;

    /** 云量百分比（0-100），对应 rs_image.cloud_percent。 */
    private BigDecimal cloudPercent;

    /** 空间分辨率（米），对应 rs_image.resolution_meter。 */
    private BigDecimal resolutionMeter;

    /** 影像像素宽度，对应 rs_image.width。 */
    private Integer width;

    /** 影像像素高度，对应 rs_image.height。 */
    private Integer height;

    /** 原始文件在 MinIO 中的对象键，用于构造预览链接，对应 rs_image.object_key。 */
    private String objectKey;

    /** 缩略图文件在 MinIO 中的对象键，对应 rs_image.thumbnail_object_key。 */
    private String thumbnailObjectKey;

    /** 缩略图生成状态：PENDING / GENERATING / COMPLETED / FAILED，对应 rs_image.thumbnail_status。 */
    private String thumbnailStatus;

    /** 影像处理状态：READY / PROCESSING，对应 rs_image.status。 */
    private String status;

    /** 记录创建时间（UTC），对应 rs_image.created_at。 */
    private OffsetDateTime createdAt;
}
