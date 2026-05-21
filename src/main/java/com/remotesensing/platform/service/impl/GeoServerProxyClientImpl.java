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

/**
 * GeoServer HTTP 代理客户端实现类，通过 {@link HttpURLConnection} 将 OGC 请求转发至 GeoServer。
 *
 * <p>核心职责：
 * <ol>
 *   <li>校验代理路径白名单（只允许 /wms 和 /wcs）。</li>
 *   <li>构建带 Basic Auth 的 GET 请求并转发至 GeoServer。</li>
 *   <li>过滤 GeoServer 响应头，仅透传必要的响应头（Content-Type、Cache-Control、Content-Disposition）。</li>
 *   <li>使用 {@link StreamingResponseBody} 流式回传响应体，避免大文件 OOM。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>使用 {@link HttpURLConnection} 而非 RestTemplate / WebClient，因为需要精细控制连接生命周期
 *       （响应完成后主动 {@code disconnect()} 释放连接）。</li>
 *   <li>错误响应流（status >= 400）通过 {@code getErrorStream()} 读取，确保完整获取 GeoServer 错误信息。</li>
 *   <li>Basic Auth 每次实时计算而非缓存，避免 credentials 变更后使用过期值。</li>
 * </ul>
 *
 * @author remote-sensing-platform
 */
@Component
public class GeoServerProxyClientImpl implements GeoServerProxyClient {

    /** 允许代理转发的 GeoServer 端点路径列表 */
    private static final List<String> ALLOWED_PATHS = List.of("/wms", "/wcs");
    /** 从 GeoServer 响应中保留并透传给客户端的响应头名称列表 */
    private static final List<String> RESPONSE_HEADERS_TO_KEEP = List.of(
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.CACHE_CONTROL,
            HttpHeaders.CONTENT_DISPOSITION
    );

    private final GeoServerProperties geoServerProperties;

    public GeoServerProxyClientImpl(GeoServerProperties geoServerProperties) {
        this.geoServerProperties = geoServerProperties;
    }

    /**
     * 将 OGC 请求代理转发至 GeoServer，返回流式响应。
     *
     * <p>处理流程：
     * <ol>
     *   <li>校验 {@code path} 是否在允许列表中。</li>
     *   <li>打开到 GeoServer 的 HTTP 连接（GET 方式）。</li>
     *   <li>获取响应状态码和过滤后的响应头。</li>
     *   <li>构造 {@link StreamingResponseBody}，在客户端消费响应体时从 GeoServer 拉取数据。</li>
     * </ol>
     *
     * <p>事务属性：无事务。此操作是纯转发，不涉及数据库操作。
     *
     * @param path        GeoServer 端点路径，如 /wms、/wcs
     * @param queryParams 已校验和过滤后的查询参数
     * @return 包含 GeoServer 原始状态码、过滤后响应头和流式响应体的 ResponseEntity
     */
    @Override
    public ResponseEntity<StreamingResponseBody> proxy(String path, MultiValueMap<String, String> queryParams) {
        // 路径白名单校验，防止 Open Redirect 或 SSRF 攻击
        if (!ALLOWED_PATHS.contains(path)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "GeoServer 代理路径不允许访问");
        }

        // 建立与 GeoServer 的 HTTP 连接
        HttpURLConnection connection = openConnection(path, queryParams);
        int statusCode = responseCode(connection);
        // 只保留必要的响应头，避免将 GeoServer 的内部响应头信息泄露给客户端
        HttpHeaders headers = filterResponseHeaders(connection);
        // 使用 StreamingResponseBody 流式回传，避免将整个 GeoTIFF/图片加载到内存中
        StreamingResponseBody body = outputStream -> {
            try (InputStream inputStream = responseStream(connection, statusCode)) {
                if (inputStream != null) {
                    StreamUtils.copy(inputStream, outputStream);
                }
            } finally {
                // 无论成功还是异常，均需释放 HTTP 连接资源
                connection.disconnect();
            }
        };
        return ResponseEntity.status(statusCode)
                .headers(headers)
                .body(body);
    }

    /**
     * 建立到 GeoServer 的 HTTP GET 连接。
     * <p>配置连接超时和读取超时（通过配置获取），并设置 Basic Auth 和 Accept 头。
     *
     * @param path        GeoServer 端点路径
     * @param queryParams 查询参数
     * @return 已打开的 HttpURLConnection（但尚未获取响应）
     */
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

    /**
     * 构建完整的 GeoServer 请求 URI，包含主机地址、路径和所有查询参数。
     * <p>支持同一参数名多个值（如 {@code bbox=-180,-90,180,90} + {@code bbox=0,0,1,1}，
     * 这是 OGC 服务中的合法用法）。
     */
    private URI buildUri(String path, MultiValueMap<String, String> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(trimTrailingSlash(geoServerProperties.getUrl()))
                .path(path);
        queryParams.forEach((key, values) -> values.forEach(value -> builder.queryParam(key, value)));
        return builder.build().encode().toUri();
    }

    /**
     * 从连接中获取 HTTP 响应状态码。获取异常时主动断开连接并抛出业务异常。
     */
    private int responseCode(HttpURLConnection connection) {
        try {
            return connection.getResponseCode();
        } catch (IOException exception) {
            connection.disconnect();
            throw new BusinessException(ResultCode.FAIL.getCode(), "获取 GeoServer 响应状态失败：" + exception.getMessage());
        }
    }

    /**
     * 根据 HTTP 状态码选择正确的响应输入流。
     * <p>状态码 >= 400 时使用 {@link HttpURLConnection#getErrorStream()}，
     * 该流包含了 GeoServer 返回的错误详情（如 XML/JSON 错误描述）。
     */
    private InputStream responseStream(HttpURLConnection connection, int statusCode) throws IOException {
        if (statusCode >= 400) {
            return connection.getErrorStream();
        }
        return connection.getInputStream();
    }

    /**
     * 过滤 GeoServer 的响应头，只保留 {@link #RESPONSE_HEADERS_TO_KEEP} 中声明的头部。
     * <p>这是安全措施——避免将 GeoServer 的 Server 版本、响应时间等内部信息暴露给客户端。
     */
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

    /**
     * 构造用于 GeoServer Basic 认证的 HTTP Authorization 头值。
     * <p>格式为 {@code Basic base64(username:password)}。
     * 每次调用实时计算，确保在 GeoServer 密码变更后能够立即生效（无需重启）。
     */
    private String basicAuthHeader() {
        String raw = geoServerProperties.getUsername() + ":" + geoServerProperties.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 去除 URL 末尾的斜杠。
     * <p>用于拼接路径时避免出现双斜杠（如 {@code http://host//wms}）。
     */
    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
