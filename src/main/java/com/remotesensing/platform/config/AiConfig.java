package com.remotesensing.platform.config;

import com.remotesensing.platform.config.properties.AiProperties;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AI 模型客户端配置。
 *
 * <p>创建专用于调用大模型 API 的 {@link RestClient} Bean，与 GeoServer 的 RestClient
 * 通过 {@link Qualifier} 隔离，避免 baseUrl 和超时配置互相污染。</p>
 *
 * <p>对接协议为 OpenAI-compatible Chat Completions，当前默认指向 DeepSeek，
 * 可通过 {@link AiProperties} 切换为其他兼容服务（如 OpenAI、Ollama 等）。</p>
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    /**
     * 创建 AI 模型调用的 RestClient Bean。
     *
     * <p>连接超时和读取超时共用 {@link AiProperties#getTimeoutSeconds()}，
     * 默认 30 秒，平衡模型推理耗时与用户体验。</p>
     *
     * @param properties AI 配置属性
     * @return 配置完成的 RestClient 实例
     */
    @Bean
    @Qualifier("aiRestClient")
    public RestClient aiRestClient(AiProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));
        requestFactory.setReadTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()));

        return RestClient.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory)
                .build();
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
