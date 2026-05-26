package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.config.properties.AiProperties;
import com.remotesensing.platform.dto.LlmMessage;
import com.remotesensing.platform.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleLlmClientTest {

    @Test
    void chatJsonShouldFailWhenApiKeyMissing() {
        AiProperties properties = new AiProperties();
        properties.setApiKey("");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
                RestClient.create("http://127.0.0.1:19999/v1"),
                properties,
                new ObjectMapper()
        );

        assertThatThrownBy(() -> client.chatJson(List.of(new LlmMessage("user", "hello"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 服务未配置");
    }
}
