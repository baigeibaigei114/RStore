package com.remotesensing.platform.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.RateLimitService;
import com.remotesensing.platform.service.AiQueryParseService;
import com.remotesensing.platform.service.AiReportService;
import com.remotesensing.platform.vo.AiQueryIntentVO;
import com.remotesensing.platform.vo.AiReportVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(AiController.class)
@Import(TestConfig.class)
class AiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiQueryParseService aiQueryParseService;

    @MockBean
    private AiReportService aiReportService;

    @Autowired
    private RateLimitService rateLimitService;

    @AfterEach
    void tearDown() {
        reset(rateLimitService);
    }

    @Test
    @DisplayName("自然语言查询解析接口返回结构化条件")
    void parseQueryShouldReturnStructuredIntent() throws Exception {
        AiQueryIntentVO intent = new AiQueryIntentVO();
        intent.setRegionName("黄浦区");
        intent.setStartTime(OffsetDateTime.parse("2024-01-01T00:00:00+08:00"));
        intent.setEndTime(OffsetDateTime.parse("2024-12-31T23:59:59+08:00"));
        intent.setSensor("Sentinel-2");
        intent.setMaxCloudPercent(new BigDecimal("20"));
        intent.setTaskTypes(List.of(TaskType.NDVI));
        when(aiQueryParseService.parse(eq("找上海黄浦区2024年云量小于20%的Sentinel-2影像，并计算NDVI")))
                .thenReturn(intent);

        mockMvc.perform(post("/api/ai/query/parse")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "text": "找上海黄浦区2024年云量小于20%的Sentinel-2影像，并计算NDVI"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.regionName").value("黄浦区"))
                .andExpect(jsonPath("$.data.taskTypes[0]").value("NDVI"));

        verify(aiQueryParseService).parse(eq("找上海黄浦区2024年云量小于20%的Sentinel-2影像，并计算NDVI"));
        verify(rateLimitService).check("ai-query:user:dev-user", 20, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("任务报告生成接口返回报告")
    void generateReportShouldReturnReport() throws Exception {
        AiReportVO report = new AiReportVO();
        report.setId(1L);
        report.setTaskId(10L);
        report.setReportType("NDVI");
        report.setSummary("该区域整体植被覆盖可能较好。");
        report.setReportJson(Map.of("riskLevel", "LOW"));
        when(aiReportService.generateFromTask(10L)).thenReturn(report);

        mockMvc.perform(post("/api/ai/reports/from-task/10")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.reportJson.riskLevel").value("LOW"));

        verify(aiReportService).generateFromTask(10L);
        verify(rateLimitService).check("ai-report:user:dev-user", 3, Duration.ofSeconds(300));
    }

    @Test
    @DisplayName("AI 查询解析超过限流时不调用模型服务")
    void parseQueryShouldRejectWhenRateLimited() throws Exception {
        doThrow(new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(), ResultCode.TOO_MANY_REQUESTS.getMessage()))
                .when(rateLimitService).check("ai-query:user:dev-user", 20, Duration.ofSeconds(60));

        mockMvc.perform(post("/api/ai/query/parse")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"找上海影像\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.TOO_MANY_REQUESTS.getCode()));

        verify(aiQueryParseService, never()).parse(org.mockito.ArgumentMatchers.anyString());
    }
}
