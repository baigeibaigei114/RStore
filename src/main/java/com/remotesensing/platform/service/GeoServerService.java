package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.GeoServerPublishVO;

/**
 * GeoServer 发布服务接口。
 * <p>
 * 负责将遥感处理结果文件发布为 GeoServer 图层，提供 WMS 和 WCS 服务。
 * 支持同步发布和异步发布两种方式。
 */
public interface GeoServerService {

    /**
     * 创建 GeoServer workspace（工作空间）。
     * 如果已存在则直接视为成功。
     *
     * @param workspace 工作空间名称
     */
    void createWorkspace(String workspace);

    /**
     * 通过 GeoServer 可访问的 GeoTIFF 位置创建 coverage store，
     * 并自动配置同名 coverage/layer，使得该图层可通过 WMS/WCS 访问。
     *
     * @param workspace      工作空间名称
     * @param storeName      CoverageStore 名称
     * @param layerName      图层名称
     * @param geoTiffLocation GeoServer 可访问的 GeoTIFF 文件路径
     */
    void createCoverageStore(String workspace, String storeName, String layerName, String geoTiffLocation);

    /**
     * 同步发布任务结果到 GeoServer。
     * 将结果文件发布为图层，并更新结果文件记录的发布状态。
     *
     * @param taskId 任务 ID
     * @return 发布结果信息（工作空间、图层名称、WMS/WCS URL 等）
     */
    GeoServerPublishVO publishTaskResult(Long taskId);

    /**
     * 异步发布任务结果到 GeoServer。
     * 使用 geoServerPublishExecutor 线程池，避免发布操作阻塞 Web 线程。
     *
     * @param taskId 任务 ID
     */
    void publishTaskResultAsync(Long taskId);
}
