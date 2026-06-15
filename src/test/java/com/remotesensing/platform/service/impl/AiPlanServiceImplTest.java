package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.enums.AiPlanStatus;
import com.remotesensing.platform.dto.AiPlanSearchDTO;
import com.remotesensing.platform.dto.LlmMessage;
import com.remotesensing.platform.entity.AiPlan;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.AiPlanMapper;
import com.remotesensing.platform.service.LlmClient;
import com.remotesensing.platform.vo.AiPlanListVO;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AiPlanServiceImplTest {

    private final CurrentUserContext currentUserContext = Mockito.mock(CurrentUserContext.class);
    private final AiPlanMapper aiPlanMapper = Mockito.mock(AiPlanMapper.class);
    private final LlmClient llmClient = Mockito.mock(LlmClient.class);
    private final PlatformTransactionManager transactionManager = Mockito.mock(PlatformTransactionManager.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiPlanValidatorImpl validator = new AiPlanValidatorImpl();
    private final AiPlanServiceImpl service = new AiPlanServiceImpl(
            currentUserContext,
            aiPlanMapper,
            validator,
            llmClient,
            objectMapper,
            transactionManager
    );

    @BeforeEach
    void setUp() {
        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(aiPlanMapper.insert(any(AiPlan.class))).thenAnswer(invocation -> {
            AiPlan plan = invocation.getArgument(0);
            plan.setId(1L);
            return 1;
        });
        when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void createShouldSaveValidPlan() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn(validPlanJson());

        var request = new com.remotesensing.platform.dto.AiPlanCreateDTO();
        request.setText("查找黄浦区 Sentinel-2 影像并计算 NDVI");

        var result = service.create(request);

        assertThat(result.getStatus()).isEqualTo(AiPlanStatus.VALID.name());
        assertThat(result.getPlan().getSteps()).hasSize(5);
        verify(aiPlanMapper).insert(ArgumentMatchers.argThat(plan ->
                "user-a".equals(plan.getUserId())
                        && AiPlanStatus.VALID.name().equals(plan.getStatus())
                        && "查找黄浦区 Sentinel-2 影像并计算 NDVI".equals(plan.getUserInput())
        ));
    }

    @Test
    void createShouldRejectNonJsonFromLlm() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("not json");
        var request = new com.remotesensing.platform.dto.AiPlanCreateDTO();
        request.setText("随便分析一下");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI Plan 生成失败");
        verify(aiPlanMapper, never()).insert(any());
    }

    @Test
    void createShouldSaveInvalidPlanWhenStepTypeUnknown() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("""
                {
                  "goal": "删除影像",
                  "steps": [
                    {
                      "order": 1,
                      "type": "DELETE_IMAGE",
                      "description": "删除影像",
                      "params": {},
                      "requiresConfirmation": true
                    }
                  ]
                }
                """);
        var request = new com.remotesensing.platform.dto.AiPlanCreateDTO();
        request.setText("删除影像");

        var result = service.create(request);

        assertThat(result.getStatus()).isEqualTo(AiPlanStatus.INVALID.name());
        assertThat(result.getValidationErrors()).anyMatch(error -> error.contains("不支持的步骤类型"));
    }

    @Test
    void createShouldMarkInvalidWhenSubmitTaskTypeUnsupported() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("""
                {
                  "goal": "执行任务",
                  "steps": [
                    {
                      "order": 1,
                      "type": "SUBMIT_TASK",
                      "description": "提交非法任务",
                      "params": {"taskType": "DELETE_IMAGE"},
                      "requiresConfirmation": true
                    }
                  ]
                }
                """);
        var request = new com.remotesensing.platform.dto.AiPlanCreateDTO();
        request.setText("执行非法任务");

        var result = service.create(request);

        assertThat(result.getStatus()).isEqualTo(AiPlanStatus.INVALID.name());
        assertThat(result.getValidationErrors()).anyMatch(error -> error.contains("taskType"));
    }

    @Test
    void createShouldMarkInvalidWhenCloudPercentOutOfRange() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("""
                {
                  "goal": "检索影像",
                  "steps": [
                    {
                      "order": 1,
                      "type": "SEARCH_IMAGES",
                      "description": "云量条件非法",
                      "params": {"maxCloudPercent": 120},
                      "requiresConfirmation": false
                    }
                  ]
                }
                """);
        var request = new com.remotesensing.platform.dto.AiPlanCreateDTO();
        request.setText("云量小于 120");

        var result = service.create(request);

        assertThat(result.getStatus()).isEqualTo(AiPlanStatus.INVALID.name());
        assertThat(result.getValidationErrors()).anyMatch(error -> error.contains("maxCloudPercent"));
    }

    @Test
    void createShouldMarkInvalidWhenSubmitTaskDoesNotRequireConfirmation() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("""
                {
                  "goal": "计算 NDVI",
                  "steps": [
                    {
                      "order": 1,
                      "type": "SUBMIT_TASK",
                      "description": "提交 NDVI 任务",
                      "params": {"taskType": "NDVI"},
                      "requiresConfirmation": false
                    }
                  ]
                }
                """);
        var request = new com.remotesensing.platform.dto.AiPlanCreateDTO();
        request.setText("直接算 NDVI");

        var result = service.create(request);

        assertThat(result.getStatus()).isEqualTo(AiPlanStatus.INVALID.name());
        assertThat(result.getValidationErrors()).anyMatch(error -> error.contains("requiresConfirmation=true"));
    }

    @Test
    void createShouldMarkInvalidWhenTooManySteps() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("""
                {
                  "goal": "过长计划",
                  "steps": [
                    {"order":1,"type":"LIST_LAYERS","description":"1","params":{},"requiresConfirmation":false},
                    {"order":2,"type":"LIST_LAYERS","description":"2","params":{},"requiresConfirmation":false},
                    {"order":3,"type":"LIST_LAYERS","description":"3","params":{},"requiresConfirmation":false},
                    {"order":4,"type":"LIST_LAYERS","description":"4","params":{},"requiresConfirmation":false},
                    {"order":5,"type":"LIST_LAYERS","description":"5","params":{},"requiresConfirmation":false},
                    {"order":6,"type":"LIST_LAYERS","description":"6","params":{},"requiresConfirmation":false},
                    {"order":7,"type":"LIST_LAYERS","description":"7","params":{},"requiresConfirmation":false},
                    {"order":8,"type":"LIST_LAYERS","description":"8","params":{},"requiresConfirmation":false},
                    {"order":9,"type":"LIST_LAYERS","description":"9","params":{},"requiresConfirmation":false},
                    {"order":10,"type":"LIST_LAYERS","description":"10","params":{},"requiresConfirmation":false},
                    {"order":11,"type":"LIST_LAYERS","description":"11","params":{},"requiresConfirmation":false}
                  ]
                }
                """);
        var request = new com.remotesensing.platform.dto.AiPlanCreateDTO();
        request.setText("生成很多步骤");

        var result = service.create(request);

        assertThat(result.getStatus()).isEqualTo(AiPlanStatus.INVALID.name());
        assertThat(result.getValidationErrors()).anyMatch(error -> error.contains("steps 不能超过"));
    }

    @Test
    void createShouldMarkInvalidWhenImageIdIsNotPositive() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("""
                {
                  "goal": "提交任务",
                  "steps": [
                    {
                      "order": 1,
                      "type": "SUBMIT_TASK",
                      "description": "提交 NDVI 任务",
                      "params": {"taskType": "NDVI", "imageId": -1},
                      "requiresConfirmation": true
                    }
                  ]
                }
                """);
        var request = new com.remotesensing.platform.dto.AiPlanCreateDTO();
        request.setText("对 -1 影像算 NDVI");

        var result = service.create(request);

        assertThat(result.getStatus()).isEqualTo(AiPlanStatus.INVALID.name());
        assertThat(result.getValidationErrors()).anyMatch(error -> error.contains("imageId"));
    }

    @Test
    void createShouldMarkInvalidWhenBboxOutOfLngLatRange() {
        when(llmClient.chatJson(ArgumentMatchers.<List<LlmMessage>>any())).thenReturn("""
                {
                  "goal": "空间检索",
                  "steps": [
                    {
                      "order": 1,
                      "type": "SEARCH_IMAGES",
                      "description": "检索非法 bbox",
                      "params": {"bbox": "999,10,1000,20"},
                      "requiresConfirmation": false
                    }
                  ]
                }
                """);
        var request = new com.remotesensing.platform.dto.AiPlanCreateDTO();
        request.setText("检索非法 bbox");

        var result = service.create(request);

        assertThat(result.getStatus()).isEqualTo(AiPlanStatus.INVALID.name());
        assertThat(result.getValidationErrors()).anyMatch(error -> error.contains("经度范围"));
    }

    @Test
    void getByIdShouldOnlyLoadCurrentUsersPlan() throws Exception {
        AiPlan plan = planEntity(AiPlanStatus.VALID.name());
        when(aiPlanMapper.selectByIdForUser(1L, "user-a")).thenReturn(plan);

        var result = service.getById(1L);

        assertThat(result.getId()).isEqualTo(1L);
        verify(aiPlanMapper).selectByIdForUser(1L, "user-a");
    }

    @Test
    void getByIdShouldRejectWhenPlanNotOwned() {
        when(aiPlanMapper.selectByIdForUser(1L, "user-a")).thenReturn(null);

        assertThatThrownBy(() -> service.getById(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在或无权访问");
    }

    @Test
    void confirmShouldOnlyAllowValidPlan() throws Exception {
        AiPlan plan = planEntity(AiPlanStatus.VALID.name());
        when(aiPlanMapper.selectByIdForUser(1L, "user-a"))
                .thenReturn(plan)
                .thenReturn(withStatus(plan, AiPlanStatus.CONFIRMED.name()));

        var result = service.confirm(1L);

        assertThat(result.getStatus()).isEqualTo(AiPlanStatus.CONFIRMED.name());
        verify(aiPlanMapper).updateStatusForUser(1L, "user-a", AiPlanStatus.CONFIRMED.name());
    }

    @Test
    void confirmShouldRejectInvalidPlan() throws Exception {
        AiPlan plan = planEntity(AiPlanStatus.INVALID.name());
        when(aiPlanMapper.selectByIdForUser(1L, "user-a")).thenReturn(plan);

        assertThatThrownBy(() -> service.confirm(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("VALID");
        verify(aiPlanMapper, never()).updateStatusForUser(any(), any(), any());
    }

    @Test
    void cancelShouldUpdateOwnPlan() throws Exception {
        AiPlan plan = planEntity(AiPlanStatus.VALID.name());
        when(aiPlanMapper.selectByIdForUser(1L, "user-a"))
                .thenReturn(plan)
                .thenReturn(withStatus(plan, AiPlanStatus.CANCELED.name()));

        var result = service.cancel(1L);

        assertThat(result.getStatus()).isEqualTo(AiPlanStatus.CANCELED.name());
        verify(aiPlanMapper).updateStatusForUser(1L, "user-a", AiPlanStatus.CANCELED.name());
    }

    @Test
    void cancelShouldRejectConfirmedPlan() throws Exception {
        AiPlan plan = planEntity(AiPlanStatus.CONFIRMED.name());
        when(aiPlanMapper.selectByIdForUser(1L, "user-a")).thenReturn(plan);

        assertThatThrownBy(() -> service.cancel(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已确认");
    }

    @Test
    void pageShouldQueryCurrentUserPlans() throws Exception {
        AiPlan plan = planEntity(AiPlanStatus.VALID.name());
        when(aiPlanMapper.pageByUser(eq("user-a"), any(AiPlanSearchDTO.class), eq(10), eq(0)))
                .thenReturn(List.of(plan));
        when(aiPlanMapper.countByUser(eq("user-a"), any(AiPlanSearchDTO.class))).thenReturn(1L);

        PageResult<AiPlanListVO> result = service.page(new AiPlanSearchDTO(), 1, 10);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1L);
    }

    private String validPlanJson() {
        return """
                {
                  "goal": "上海黄浦区2024年低云量 Sentinel-2 影像 NDVI 分析",
                  "steps": [
                    {
                      "order": 1,
                      "type": "RESOLVE_REGION",
                      "description": "查询行政区：黄浦区",
                      "params": {"regionName": "黄浦区"},
                      "requiresConfirmation": false
                    },
                    {
                      "order": 2,
                      "type": "SEARCH_IMAGES",
                      "description": "检索2024年云量小于20%的Sentinel-2影像",
                      "params": {
                        "regionName": "黄浦区",
                        "startTime": "2024-01-01T00:00:00+08:00",
                        "endTime": "2024-12-31T23:59:59+08:00",
                        "sensor": "Sentinel-2",
                        "maxCloudPercent": 20
                      },
                      "requiresConfirmation": false
                    },
                    {
                      "order": 3,
                      "type": "SELECT_IMAGE",
                      "description": "由用户选择要处理的影像",
                      "params": {},
                      "requiresConfirmation": true
                    },
                    {
                      "order": 4,
                      "type": "SUBMIT_TASK",
                      "description": "提交 NDVI 处理任务",
                      "params": {"taskType": "NDVI"},
                      "requiresConfirmation": true
                    },
                    {
                      "order": 5,
                      "type": "GENERATE_REPORT",
                      "description": "任务完成后生成 AI 分析报告",
                      "params": {"reportType": "NDVI"},
                      "requiresConfirmation": true
                    }
                  ]
                }
                """;
    }

    private AiPlan planEntity(String status) throws JsonProcessingException {
        AiPlan plan = new AiPlan();
        plan.setId(1L);
        plan.setUserId("user-a");
        plan.setUserInput("查找影像并计算 NDVI");
        plan.setTitle("上海黄浦区2024年低云量 Sentinel-2 影像 NDVI 分析");
        plan.setStatus(status);
        plan.setPlanJson(validPlanJson());
        plan.setValidationErrors(objectMapper.writeValueAsString(List.of()));
        return plan;
    }

    private AiPlan withStatus(AiPlan source, String status) {
        AiPlan copy = new AiPlan();
        copy.setId(source.getId());
        copy.setUserId(source.getUserId());
        copy.setUserInput(source.getUserInput());
        copy.setTitle(source.getTitle());
        copy.setStatus(status);
        copy.setPlanJson(source.getPlanJson());
        copy.setValidationErrors(source.getValidationErrors());
        return copy;
    }
}
