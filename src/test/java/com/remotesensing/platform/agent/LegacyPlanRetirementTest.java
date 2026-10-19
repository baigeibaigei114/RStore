package com.remotesensing.platform.agent;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.controller.AiController;
import com.remotesensing.platform.service.*;
import com.remotesensing.platform.config.properties.RateLimitProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LegacyPlanRetirementTest {
    @Test void retiredPlanClassesAndMapperAreAbsentFromCleanRuntimeClasspath() {
        for (String name : List.of("controller.AiPlanController", "service.AiPlanService", "service.impl.AiPlanServiceImpl",
                "service.AiPlanValidator", "service.impl.AiPlanValidatorImpl", "mapper.AiPlanMapper", "entity.AiPlan",
                "dto.AiPlanCreateDTO", "dto.AiPlanContentDTO", "common.enums.AiPlanStatus", "common.enums.AiPlanStepType"))
            assertThatThrownBy(() -> Class.forName("com.remotesensing.platform."+name)).isInstanceOf(ClassNotFoundException.class);
        assertThat(getClass().getClassLoader().getResource("mapper/AiPlanMapper.xml")).isNull();
    }
    @Test void legacyPathsHaveNoHandlerWhileSharedAiAndNewAgentMappingsRemain() throws Exception {
        var users = mock(CurrentUserContext.class);
        var ai = new AiController(mock(AiQueryParseService.class),mock(AiReportService.class),mock(RateLimitService.class),new RateLimitProperties(),users);
        var agentService = mock(AgentService.class);
        var mvc = MockMvcBuilders.standaloneSetup(ai,new AgentController(agentService,users,mock(RateLimitService.class),new AgentProperties()))
                .setControllerAdvice(new com.remotesensing.platform.exception.GlobalExceptionHandler()).build();
        for (var request : List.of(get("/api/ai/plans"),post("/api/ai/plans"),get("/api/ai/plans/1"),patch("/api/ai/plans/1/confirm"),patch("/api/ai/plans/1/cancel")))
            mvc.perform(request.contextPath("/api")).andExpect(status().isNotFound());
        var mapping = mvc.getDispatcherServlet().getWebApplicationContext().getBean(RequestMappingHandlerMapping.class);
        String paths = mapping.getHandlerMethods().keySet().toString();
        assertThat(paths).contains("/agent/executions", "/agent/answers", "/ai/query/parse", "/ai/reports/from-task/{taskId}");
        verifyNoInteractions(agentService);
    }
}
