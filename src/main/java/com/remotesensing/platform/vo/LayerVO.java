package com.remotesensing.platform.vo;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 图层 VO。
 * 用于展示已发布到 GeoServer 的图层信息，包含图层属性、OGC 服务代理 URL 及关联影像/任务信息。
 * 通过 JOIN rs_result_file 和 rs_image 表组装，用于前端图层管理页面展示。
 */
@Data
public class LayerVO {

    /** 结果文件主键 ID，对应 rs_result_file.id。 */
    private Long id;

    /** 关联的任务主键 ID，对应 rs_result_file.task_id。 */
    private Long taskId;

    /** 关联的影像主键 ID，对应 rs_result_file.image_id。 */
    private Long imageId;

    /** 关联影像的名称，通过 JOIN rs_image 查询得到。 */
    private String imageName;

    /** 关联影像的软删除时间（UTC），用于判断原始影像是否已被删除，通过 JOIN rs_image 查询。 */
    private OffsetDateTime imageDeletedAt;

    /** 任务类型，如 NDVI / NDWI / CHANGE_DETECTION，对应 rs_task.task_type。 */
    private String taskType;

    /** 任务名称，对应 rs_task.task_name。 */
    private String taskName;

    /** 文件名，对应 rs_result_file.file_name。 */
    private String fileName;

    /** 文件类型，对应 rs_result_file.file_type。 */
    private String fileType;

    /** 图层所属用户 ID，对应 rs_result_file.owner_id。 */
    private String ownerId;

    /** 可见性：PUBLIC（公开）/ PRIVATE（私有），对应 rs_result_file.visibility。 */
    private String visibility;

    /** GeoServer 工作空间名称，对应 rs_result_file.workspace。 */
    private String workspace;

    /** GeoServer 存储名称（CoverageStore），对应 rs_result_file.store_name。 */
    private String storeName;

    /** GeoServer 图层名称，对应 rs_result_file.layer_name。 */
    private String layerName;

    /** GeoServer 限定图层名称（workspace:layerName 格式），用于 OGC 请求中的图层标识。 */
    private String qualifiedLayerName;

    /** WMS 服务代理 URL（经过网关代理，隐藏真实 GeoServer 地址），对应 rs_result_file.wms_url。 */
    private String proxyWmsUrl;

    /** WCS 服务代理 URL（经过网关代理，隐藏真实 GeoServer 地址），对应 rs_result_file.wcs_url。 */
    private String proxyWcsUrl;

    /** 发布时间（UTC），对应 rs_result_file.published_at。 */
    private OffsetDateTime publishedAt;

    /** 记录创建时间（UTC），对应 rs_result_file.created_at。 */
    private OffsetDateTime createdAt;

    /** 记录更新时间（UTC），对应 rs_result_file.updated_at。 */
    private OffsetDateTime updatedAt;
}
