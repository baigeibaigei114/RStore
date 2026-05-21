package com.remotesensing.platform.config;

import com.remotesensing.platform.config.properties.GeoServerProperties;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * GeoServer 集成配置类。
 * <p>
 * 职责：
 * - 配置与 GeoServer 通信的 RestClient（含 BasicAuth 认证和超时设置）。
 * - 提供用于异步发布图层到 GeoServer 的线程池。
 */
@Configuration
@EnableConfigurationProperties(GeoServerProperties.class)
public class GeoServerConfig {

    /**
     * GeoServer REST API 客户端 Bean。
     * 配置连接超时（默认 5 秒）和读取超时（默认 30 秒），避免因 GeoServer 响应慢而阻塞 Web 线程。
     */
    @Bean
    public RestClient geoServerRestClient(GeoServerProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()));

        return RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getUrl()))
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> {
                    headers.setBasicAuth(properties.getUsername(), properties.getPassword());
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN));
                })
                .build();
    }

    /**
     * GeoServer 图层发布专用线程池。
     * 核心线程数 1，最大 2，队列容量 50，避免过多并发发布请求压垮 GeoServer。
     * 拒绝策略为 AbortPolicy，发布失败由上游重试逻辑处理。
     */
    @Bean(name = "geoServerPublishExecutor")
    public Executor geoServerPublishExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("rs-geoserver-publish-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /** 去除 URL 末尾的斜杠，避免 baseUrl 拼接时出现双斜杠。 */
    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
