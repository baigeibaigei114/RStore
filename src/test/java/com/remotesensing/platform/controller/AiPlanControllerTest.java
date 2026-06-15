package com.remotesensing.platform.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.dto.AiPlanContentDTO;
import com.remotesensing.platform.dto.AiPlanCreateDTO;
import com.remotesensing.platform.dto.AiPlanStepDTO;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.AiPlanService;
import com.remotesensing.platform.service.RateLimitService;
import com.remotesensing.platform.vo.AiPlanListVO;
import com.remotesensing.platform.vo.AiPlanVO;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(AiPlanController.class)
@Import(TestConfig.class)
class AiPlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AiPlanService aiPlanService;

    @Autowired
    private RateLimitService rateLimitService;

    @AfterEach
    void tearDown() {
        reset(rateLimitService);
    }

    @Test
    void createShouldReturnPlanAndCheckRateLimit() throws Exception {
        when(aiPlanService.create(any(AiPlanCreateDTO.class))).thenReturn(planVO("VALID"));

        mockMvc.perform(post("/api/ai/plans")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"查找黄浦区影像并计算 NDVI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.status").value("VALID"))
                .andExpect(jsonPath("$.data.plan.steps[0].type").value("SEARCH_IMAGES"));

        verify(rateLimitService).check("ai-plan:user:dev-user", 10, Duration.ofSeconds(60));
        verify(aiPlanService).create(any(AiPlanCreateDTO.class));
    }

    @Test
    void createShouldNotCallServiceWhenRateLimited() throws Exception {
        doThrow(new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(), ResultCode.TOO_MANY_REQUESTS.getMessage()))
                .when(rateLimitService).check("ai-plan:user:dev-user", 10, Duration.ofSeconds(60));

        mockMvc.perform(post("/api/ai/plans")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"查找影像\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.TOO_MANY_REQUESTS.getCode()));

        verify(aiPlanService, never()).create(any());
    }

    @Test
    void getByIdShouldReturnOwnPlan() throws Exception {
        when(aiPlanService.getById(1L)).thenReturn(planVO("VALID"));

        mockMvc.perform(get("/api/ai/plans/1")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1L));
    }

    @Test
    void pageShouldReturnCurrentUserPlans() throws Exception {
        AiPlanListVO item = new AiPlanListVO();
        item.setId(1L);
        item.setStatus("VALID");
        item.setTitle("影像分析计划");
        when(aiPlanService.page(any(), ArgumentMatchers.eq(1), ArgumentMatchers.eq(10)))
                .thenReturn(new PageResult<>(List.of(item), 1L, 1, 10));

        mockMvc.perform(get("/api/ai/plans")
                        .contextPath("/api")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].id").value(1L))
                .andExpect(jsonPath("$.data.total").value(1L));
    }

    @Test
    void confirmShouldCallService() throws Exception {
        when(aiPlanService.confirm(1L)).thenReturn(planVO("CONFIRMED"));

        mockMvc.perform(patch("/api/ai/plans/1/confirm")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void cancelShouldCallService() throws Exception {
        when(aiPlanService.cancel(1L)).thenReturn(planVO("CANCELED"));

        mockMvc.perform(patch("/api/ai/plans/1/cancel")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELED"));
    }

    private AiPlanVO planVO(String status) {
        AiPlanStepDTO step = new AiPlanStepDTO();
        step.setOrder(1);
        step.setType("SEARCH_IMAGES");
        step.setDescription("检索影像");
        step.setParams(Map.of("sensor", "Sentinel-2"));
        step.setRequiresConfirmation(false);

        AiPlanContentDTO plan = new AiPlanContentDTO();
        plan.setGoal("检索影像并计算 NDVI");
        plan.setSteps(List.of(step));

        AiPlanVO vo = new AiPlanVO();
        vo.setId(1L);
        vo.setStatus(status);
        vo.setTitle("影像分析计划");
        vo.setUserInput("查找黄浦区影像并计算 NDVI");
        vo.setPlan(plan);
        vo.setValidationErrors(List.of());
        return vo;
    }
}
