package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.properties.AiProperties;
import com.remotesensing.platform.dto.LlmMessage;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.LlmClient;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenAI-compatible Chat Completions 客户端实现。
 *
 * <p>通过 HTTP POST 调用 {@code /v1/chat/completions} 协议，适配 DeepSeek、OpenAI、
 * Ollama 等兼容服务。使用 {@code response_format: json_object} 强制模型返回合法 JSON，
 * 避免下游解析异常。</p>
 *
 * <p>异常映射策略：HTTP 4xx/5xx 按状态码分类翻译为业务异常，避免直接暴露原始错误信息。
 * 网络超时或连接失败统一返回"AI 服务暂不可用"。</p>
 */
@Service
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final RestClient aiRestClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    /**
     * @param aiRestClient 通过 {@link Qualifier} 注入的独立 RestClient，不与 GeoServer 客户端共用
     * @param aiProperties AI 配置属性
     * @param objectMapper Jackson 序列化/反序列化
     */
    public OpenAiCompatibleLlmClient(@Qualifier("aiRestClient") RestClient aiRestClient,
                                     AiProperties aiProperties,
                                     ObjectMapper objectMapper) {
        this.aiRestClient = aiRestClient;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用 LLM 并要求返回 JSON 字符串。
     *
     * <p>发送前过三道闸门：{@code enabled} 总开关、provider 类型校验、apiKey 非空。
     * 使用 {@code max_tokens} 限制输出长度，避免模型异常时产生超长响应。</p>
     *
     * @param messages Chat Completions 格式的消息列表
     * @return 模型返回的 content 字符串（应为合法 JSON）
     * @throws BusinessException 未启用/未配置/调用失败时抛出
     */
    @Override
    public String chatJson(List<LlmMessage> messages) {
        validateConfigured();
        Map<String, Object> requestBody = Map.of(
                "model", aiProperties.getModel(),
                "messages", messages,
                "temperature", aiProperties.getTemperature(),
                "max_tokens", aiProperties.getMaxTokens(),
                "response_format", Map.of("type", "json_object")
        );

        try {
            String responseBody = aiRestClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + aiProperties.getApiKey())
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
            return extractContent(responseBody);
        } catch (RestClientResponseException exception) {
            throw mapLlmException(exception);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "AI 服务暂不可用");
        }
    }

    /**
     * 服务可用性前置校验 —— 总开关、协议类型、API Key 三关。
     */
    private void validateConfigured() {
        if (!aiProperties.isEnabled()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 服务未启用");
        }
        if (!"openai-compatible".equalsIgnoreCase(aiProperties.getProvider())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "暂不支持的 AI provider：" + aiProperties.getProvider());
        }
        if (aiProperties.getApiKey() == null || aiProperties.getApiKey().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 服务未配置");
        }
    }

    /**
     * 从 Chat Completions 响应中提取 content 字段。
     *
     * <p>OpenAI-compatible 响应结构固定为 {@code choices[0].message.content}，
     * 提取后做空值校验 —— 模型可能返回空字符串。</p>
     */
    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (!contentNode.isTextual() || contentNode.asText().isBlank()) {
                throw new BusinessException(ResultCode.FAIL.getCode(), "AI 响应格式异常");
            }
            return contentNode.asText();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException | java.io.IOException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "AI 响应格式异常");
        }
    }

    /**
     * HTTP 状态码 → 业务异常的翻译。
     *
     * <p>401/403 → 认证失败（API Key 无效或过期）；
     * 429 → 触发模型侧限流，等待客户端重试；
     * 5xx → 模型服务端故障；
     * 其他 → 泛化错误提示。</p>
     */
    private BusinessException mapLlmException(RestClientResponseException exception) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            return new BusinessException(ResultCode.UNAUTHORIZED.getCode(), "AI 服务认证失败");
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            return new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(), "AI 服务请求过于频繁");
        }
        if (status != null && status.is5xxServerError()) {
            return new BusinessException(ResultCode.FAIL.getCode(), "AI 服务暂不可用");
        }
        return new BusinessException(ResultCode.FAIL.getCode(), "AI 服务调用失败");
    }
}
