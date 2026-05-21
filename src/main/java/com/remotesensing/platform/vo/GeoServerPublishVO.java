package com.remotesensing.platform.vo;

import lombok.Data;

/**
 * GeoServer 图层发布响应 VO。
 * 用于返回任务结果文件发布到 GeoServer 后的图层信息，包含 OGC 服务地址和预览链接。
 */
@Data
public class GeoServerPublishVO {

    /** 关联的任务主键 ID，对应 rs_task.id。 */
    private Long taskId;

    /** 图层所属用户 ID，对应 rs_result_file.owner_id。 */
    private String ownerId;

    /** 可见性：PUBLIC（公开）/ PRIVATE（私有），对应 rs_result_file.visibility。 */
    private String visibility;

    /** GeoServer 工作空间名称，发布时创建或复用。 */
    private String workspace;

    /** GeoServer 存储名称（CoverageStore），发布时创建。 */
    private String storeName;

    /** GeoServer 图层名称，发布时创建。 */
    private String layerName;

    /** GeoServer 限定图层名称（workspace:layerName 格式），OGC 请求中需使用此限定名。 */
    private String qualifiedLayerName;

    /** 源文件在 MinIO 中的对象键，GeoServer 通过该路径访问 GeoTIFF 文件。 */
    private String sourceObjectKey;

    /** WMS 服务地址（代理 URL），前端可用 OpenLayers/Leaflet 加载显示。 */
    private String wmsUrl;

    /** WCS 服务地址（代理 URL），用于获取原始栅格数据。 */
    private String wcsUrl;

    /** 图层预览 URL，可直接在浏览器中打开查看。 */
    private String layerPreviewUrl;
}
