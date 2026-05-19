package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.properties.GeoServerProperties;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.GeoServerProxyClient;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Component
public class GeoServerProxyClientImpl implements GeoServerProxyClient {

    private static final List<String> ALLOWED_PATHS = List.of("/wms", "/wcs");
    private static final List<String> RESPONSE_HEADERS_TO_KEEP = List.of(
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.CACHE_CONTROL,
            HttpHeaders.CONTENT_DISPOSITION
    );

    private final GeoServerProperties geoServerProperties;

    public GeoServerProxyClientImpl(GeoServerProperties geoServerProperties) {
        this.geoServerProperties = geoServerProperties;
    }

    @Override
    public ResponseEntity<StreamingResponseBody> proxy(String path, MultiValueMap<String, String> queryParams) {
        if (!ALLOWED_PATHS.contains(path)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "GeoServer 代理路径不允许访问");
        }

        HttpURLConnection connection = openConnection(path, queryParams);
        int statusCode = responseCode(connection);
        HttpHeaders headers = filterResponseHeaders(connection);
        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = responseStream(connection, statusCode)) {
                if (inputStream != null) {
                    StreamUtils.copy(inputStream, outputStream);
                }
            } finally {
                connection.disconnect();
            }
        };
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .body(body);
    }

    private HttpURLConnection openConnection(String path, MultiValueMap<String, String> queryParams) {
        try {
            URI uri = buildUri(path, queryParams);
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(geoServerProperties.getConnectTimeoutSeconds() * 1000);
            connection.setReadTimeout(geoServerProperties.getReadTimeoutSeconds() * 1000);
            connection.setRequestProperty(HttpHeaders.AUTHORIZATION, basicAuthHeader());
            connection.setRequestProperty(HttpHeaders.ACCEPT, "*/*");
            return connection;
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "连接 GeoServer 失败：" + exception.getMessage());
        }
    }

    private URI buildUri(String path, MultiValueMap<String, String> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(trimTrailingSlash(geoServerProperties.getUrl()))
                .path(path);
        queryParams.forEach((key, values) -> values.forEach(value -> builder.queryParam(key, value)));
        return builder.build().encode().toUri();
    }

    private int responseCode(HttpURLConnection connection) {
        try {
            return connection.getResponseCode();
        } catch (IOException exception) {
            connection.disconnect();
            throw new BusinessException(ResultCode.FAIL.getCode(), "获取 GeoServer 响应状态失败：" + exception.getMessage());
        }
    }

    private InputStream responseStream(HttpURLConnection connection, int statusCode) throws IOException {
        if (statusCode >= 400) {
            return connection.getErrorStream();
        }
        return connection.getInputStream();
    }

    private HttpHeaders filterResponseHeaders(HttpURLConnection connection) {
        HttpHeaders headers = new HttpHeaders();
        RESPONSE_HEADERS_TO_KEEP.forEach(headerName -> {
            String value = connection.getHeaderField(headerName);
            if (value != null && !value.isBlank()) {
                headers.add(headerName, value);
            }
        });
        return headers;
    }

    private String basicAuthHeader() {
        String raw = geoServerProperties.getUsername() + ":" + geoServerProperties.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
