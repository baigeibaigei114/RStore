package com.remotesensing.platform.vo;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 遥感任务结果文件 VO。
 * 用于展示任务产出的结果文件信息，包含文件属性、GeoServer 发布状态及 OGC 服务访问地址。
 * 对应 rs_result_file 表的全部字段。
 */
@Data
public class RsResultFileVO {

    /** 结果文件主键 ID，对应 rs_result_file.id。 */
    private Long id;

    /** 文件所属用户 ID，对应 rs_result_file.owner_id。 */
    private String ownerId;

    /** 可见性：PUBLIC（公开）/ PRIVATE（私有），对应 rs_result_file.visibility。 */
    private String visibility;

    /** 关联的任务主键 ID，对应 rs_result_file.task_id。 */
    private Long taskId;

    /** 关联的影像主键 ID，对应 rs_result_file.image_id。 */
    private Long imageId;

    /** 文件名，对应 rs_result_file.file_name。 */
    private String fileName;

    /** 文件类型，如 "GeoTIFF"，对应 rs_result_file.file_type。 */
    private String fileType;

    /** 文件所在 MinIO 存储桶，对应 rs_result_file.minio_bucket。 */
    private String minioBucket;

    /** 文件在 MinIO 中的对象键，对应 rs_result_file.object_key。 */
    private String objectKey;

    /** 文件大小（字节），对应 rs_result_file.file_size。 */
    private Long fileSize;

    /** 文件 MIME 类型，对应 rs_result_file.mime_type。 */
    private String mimeType;

    /** 文件校验和（如 MD5），对应 rs_result_file.checksum。 */
    private String checksum;

    /** 结果文件元数据 JSON，对应 rs_result_file.result_metadata。 */
    private String resultMetadata;

    /** 发布状态：PENDING_PUBLISH / PUBLISHING / PUBLISHED / FAILED，对应 rs_result_file.status。 */
    private String status;

    /** GeoServer 工作空间名称，对应 rs_result_file.workspace。 */
    private String workspace;

    /** GeoServer 存储名称（CoverageStore），对应 rs_result_file.store_name。 */
    private String storeName;

    /** GeoServer 图层名称，对应 rs_result_file.layer_name。 */
    private String layerName;

    /** WMS 服务代理 URL，对应 rs_result_file.wms_url。 */
    private String wmsUrl;

    /** WCS 服务代理 URL，对应 rs_result_file.wcs_url。 */
    private String wcsUrl;

    /** 发布失败时的错误信息，对应 rs_result_file.publish_error_message。 */
    private String publishErrorMessage;

    /** 发布时间（UTC），对应 rs_result_file.published_at。 */
    private OffsetDateTime publishedAt;

    /** 记录创建时间（UTC），对应 rs_result_file.created_at。 */
    private OffsetDateTime createdAt;

    /** 记录更新时间（UTC），对应 rs_result_file.updated_at。 */
    private OffsetDateTime updatedAt;
}
