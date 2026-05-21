package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 任务结果文件实体，对应 rs_result_file 表。
 * <p>
 * 记录遥感处理任务生成的结果文件信息，包括 MinIO 存储位置、GeoServer 发布状态、
 * 以及 WMS/WCS 访问 URL。结果文件在任务完成后由系统自动创建并尝试发布到 GeoServer。
 */
@Data
public class RsResultFile {

    /** 主键 ID。 */
    private Long id;

    /** 文件所有者用户 ID。 */
    private String ownerId;

    /** 可见性：PRIVATE / PUBLIC。 */
    private String visibility;

    /** 关联的任务 ID（rs_task.id）。 */
    private Long taskId;

    /** 关联的影像 ID（rs_image.id）。 */
    private Long imageId;

    /** 文件名。 */
    private String fileName;

    /** 文件类型，如 GeoTIFF、PNG 等。 */
    private String fileType;

    /** MinIO 存储桶名称。 */
    private String minioBucket;

    /** MinIO 对象键。 */
    private String objectKey;

    /** 文件大小（字节）。 */
    private Long fileSize;

    /** 文件 MIME 类型。 */
    private String mimeType;

    /** 文件校验值（如 MD5），用于完整性校验。 */
    private String checksum;

    /** 结果文件的元数据 JSON。 */
    private String resultMetadata;

    /** 发布状态：PENDING_PUBLISH / PUBLISHING / PUBLISHED / PUBLISH_FAILED。 */
    private String status;

    /** GeoServer 工作空间名称。 */
    private String workspace;

    /** GeoServer CoverageStore 名称。 */
    private String storeName;

    /** GeoServer 图层名称。 */
    private String layerName;

    /** WMS 服务访问 URL。 */
    private String wmsUrl;

    /** WCS 服务访问 URL。 */
    private String wcsUrl;

    /** 发布失败时的错误信息。 */
    private String publishErrorMessage;

    /** 发布完成时间。 */
    private OffsetDateTime publishedAt;

    /** 创建时间。 */
    private OffsetDateTime createdAt;

    /** 最后更新时间。 */
    private OffsetDateTime updatedAt;
}
