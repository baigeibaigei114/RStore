package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GeoServer 连接与发布配置属性，前缀为 "geoserver"。
 * <p>
 * 包含 GeoServer 服务端地址、认证凭据、工作空间、共享数据目录、
 * HTTP 超时以及 WMS 输出限制等配置项。
 */
@Data
@ConfigurationProperties(prefix = "geoserver")
public class GeoServerProperties {

    /** GeoServer REST API 基础地址，默认本地 8081 端口。 */
    private String url = "http://localhost:8081/geoserver";

    /** GeoServer 管理员用户名。 */
    private String username = "admin";

    /** GeoServer 管理员密码。 */
    private String password = "geoserver";

    /** 遥感影像工作空间名称。 */
    private String workspace = "remote_sensing";

    /** 共享数据目录的本地路径（对于 Spring Boot 进程而言），用于放置 GeoTIFF 文件。 */
    private String sharedDataLocalDir = "data/geoserver-raster";

    /** GeoServer 容器内可访问的共享数据目录路径，与 sharedDataLocalDir 指向同一份数据。 */
    private String sharedDataGeoServerDir = "/opt/geoserver/raster-data";

    /** 连接 GeoServer 的超时秒数，默认 5 秒。 */
    private int connectTimeoutSeconds = 5;

    /** 读取 GeoServer 响应的超时秒数，默认 30 秒（大图层发布可能耗时较长）。 */
    private int readTimeoutSeconds = 30;

    /** WMS GetMap 请求的最大宽度像素，避免生成过大的图片。默认 2048。 */
    private int wmsMaxWidth = 2048;

    /** WMS GetMap 请求的最大高度像素，默认 2048。 */
    private int wmsMaxHeight = 2048;

    /** WMS 请求是否默认启用瓦片模式（tiled=true），默认启用。 */
    private boolean wmsDefaultTiled = true;

    /** GeoServer 发布任务处于 PUBLISHING 状态的最长分钟数，超过后由补偿任务恢复为失败可重试。 */
    private int publishTimeoutMinutes = 30;
}
