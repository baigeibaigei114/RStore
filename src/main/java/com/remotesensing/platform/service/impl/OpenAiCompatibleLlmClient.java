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
 */
@Service
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final RestClient aiRestClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmClient(@Qualifier("aiRestClient") RestClient aiRestClient,
                                     AiProperties aiProperties,
                                     ObjectMapper objectMapper) {
        this.aiRestClient = aiRestClient;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
    }

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
