package com.remotesensing.platform.service;

import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * GeoServer 代理客户端接口。
 * <p>
 * 将前端发出的 WMS/WCS 请求透传到后端 GeoServer 实例，
 * 避免前端直接暴露 GeoServer 地址和认证信息。
 */
public interface GeoServerProxyClient {

    /**
     * 代理转发 WMS/WCS 请求到 GeoServer。
     * 使用流式响应（StreamingResponseBody）避免大图片在代理层缓冲。
     *
     * @param path        GeoServer 请求路径（如 /wms、/wcs）
     * @param queryParams 请求查询参数
     * @return GeoServer 的原始响应（流式传输）
     */
    ResponseEntity<StreamingResponseBody> proxy(String path, MultiValueMap<String, String> queryParams);
}
