package com.remotesensing.platform.service;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.dto.LayerSearchDTO;
import com.remotesensing.platform.vo.LayerVO;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 图层服务接口。
 * <p>
 * 提供已发布到 GeoServer 的图层面查询、WMS 和 WCS 代理转发功能。
 * 前端通过该接口获取图层列表，并通过代理接口访问 WMS/WCS 服务。
 */
public interface LayerService {

    /**
     * 分页查询已发布的图层列表。
     *
     * @param query    查询条件（任务类型、影像 ID、关键字等）
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页图层列表
     */
    PageResult<LayerVO> page(LayerSearchDTO query, Integer pageNum, Integer pageSize);

    /**
     * 代理 WMS 请求到 GeoServer。
     * 通过图层 ID 查找对应的 GeoServer WMS URL，并将请求透传。
     *
     * @param id          图层记录 ID
     * @param queryParams WMS 请求参数
     * @return GeoServer 的 WMS 响应（流式传输）
     */
    ResponseEntity<StreamingResponseBody> proxyWms(Long id, MultiValueMap<String, String> queryParams);

    /**
     * 代理 WCS 请求到 GeoServer。
     * 通过图层 ID 查找对应的 GeoServer WCS URL，并将请求透传。
     *
     * @param id          图层记录 ID
     * @param queryParams WCS 请求参数
     * @return GeoServer 的 WCS 响应（流式传输）
     */
    ResponseEntity<StreamingResponseBody> proxyWcs(Long id, MultiValueMap<String, String> queryParams);
}
