package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.dto.LlmMessage;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.LlmClient;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class AiQueryParseServiceImplTest {

    private final LlmClient llmClient = Mockito.mock(LlmClient.class);
    private final AiQueryParseServiceImpl service = new AiQueryParseServiceImpl(llmClient, new ObjectMapper());

    @Test
    void parseShouldReturnStructuredIntent() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("""
                {
                  "regionName": "黄浦区",
                  "startTime": "2024-01-01T00:00:00+08:00",
                  "endTime": "2024-12-31T23:59:59+08:00",
                  "sensor": "Sentinel-2",
                  "maxCloudPercent": 20,
                  "taskTypes": ["NDVI"]
                }
                """);

        var result = service.parse("找上海黄浦区2024年云量小于20%的Sentinel-2影像，并计算NDVI");

        assertThat(result.getRegionName()).isEqualTo("黄浦区");
        assertThat(result.getSensor()).isEqualTo("Sentinel-2");
        assertThat(result.getMaxCloudPercent()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(result.getTaskTypes()).containsExactly(TaskType.NDVI);
    }

    @Test
    void parseShouldRejectInvalidTaskType() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("""
                {"taskTypes":["DELETE_IMAGE"]}
                """);

        assertThatThrownBy(() -> service.parse("删除影像"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 解析失败");
    }

    @Test
    void parseShouldRejectCloudPercentOutOfRange() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("""
                {"maxCloudPercent":120,"taskTypes":[]}
                """);

        assertThatThrownBy(() -> service.parse("云量小于120"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 解析失败");
    }

    @Test
    void parseShouldRejectNonJsonResponse() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("不是 JSON");

        assertThatThrownBy(() -> service.parse("找上海影像"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 解析失败");
    }
}
