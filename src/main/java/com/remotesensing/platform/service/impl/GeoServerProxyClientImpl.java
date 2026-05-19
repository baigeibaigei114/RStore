package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.GeoServerProxyClient;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

@Component
public class GeoServerProxyClientImpl implements GeoServerProxyClient {

    private static final List<String> ALLOWED_PATHS = List.of("/wms", "/wcs");
    private static final List<String> RESPONSE_HEADERS_TO_KEEP = List.of(
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.CACHE_CONTROL,
            HttpHeaders.CONTENT_DISPOSITION
    );

    private final RestClient geoServerRestClient;

    public GeoServerProxyClientImpl(RestClient geoServerRestClient) {
        this.geoServerRestClient = geoServerRestClient;
    }

    @Override
    public ResponseEntity<byte[]> proxy(String path, MultiValueMap<String, String> queryParams) {
        if (!ALLOWED_PATHS.contains(path)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "GeoServer 代理路径不允许访问");
        }
        return geoServerRestClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path(path);
                    queryParams.forEach((key, values) -> values.forEach(value -> uriBuilder.queryParam(key, value)));
                    return uriBuilder.build();
                })
                .accept(MediaType.ALL)
                .exchange((request, response) -> {
                    HttpHeaders headers = filterResponseHeaders(response.getHeaders());
                    try {
                        return ResponseEntity
                                .status(response.getStatusCode())
                                .headers(headers)
                                .body(StreamUtils.copyToByteArray(response.getBody()));
                    } catch (IOException exception) {
                        throw new BusinessException(ResultCode.FAIL.getCode(), "读取 GeoServer 代理响应失败：" + exception.getMessage());
                    }
                });
    }

    private HttpHeaders filterResponseHeaders(HttpHeaders source) {
        HttpHeaders target = new HttpHeaders();
        RESPONSE_HEADERS_TO_KEEP.forEach(headerName -> {
            List<String> values = source.get(headerName);
            if (values != null && !values.isEmpty()) {
                target.put(headerName, values);
            }
        });
        return target;
    }
}
