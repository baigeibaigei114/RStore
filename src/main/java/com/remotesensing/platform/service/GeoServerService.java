package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.GeoServerPublishVO;

public interface GeoServerService {

    /**
     * 创建 GeoServer workspace；如果已存在则直接视为成功。
     */
    void createWorkspace(String workspace);

    /**
     * 通过 GeoServer 可访问的 GeoTIFF 位置创建 coverage store，并自动配置同名 coverage/layer。
     */
    void createCoverageStore(String workspace, String storeName, String layerName, String geoTiffLocation);

    GeoServerPublishVO publishTaskResult(Long taskId);

    void publishTaskResultAsync(Long taskId);
}
