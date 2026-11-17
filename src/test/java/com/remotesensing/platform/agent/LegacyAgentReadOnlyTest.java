package com.remotesensing.platform.agent;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.properties.RateLimitProperties;
import com.remotesensing.platform.controller.AiController;
import com.remotesensing.platform.service.AiQueryParseService;
import com.remotesensing.platform.service.AiReportService;
import com.remotesensing.platform.service.RateLimitService;
import com.remotesensing.platform.vo.AiReportVO;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LegacyAgentReadOnlyTest {
    private final AgentService agent = mock(AgentService.class);
    private final AiReportService reports = mock(AiReportService.class);
    private final RateLimitService rate = mock(RateLimitService.class);
    private final CurrentUserContext users = mock(CurrentUserContext.class);

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new AgentController(agent),
                new AiController(mock(AiQueryParseService.class), reports, rate, new RateLimitProperties(), users))
                .setControllerAdvice(new AgentAdvice()).build();
    }

    @Test
    void closesEveryLegacyWriteWhileKeepingSavedExecutionAndReportReadable() throws Exception {
        MockMvc mvc = mvc();
        String id = UUID.randomUUID().toString();
        String execution = "/agent/executions/" + id;
        for (String path : List.of("/agent/executions", "/agent/answers", execution + "/messages",
                execution + "/confirm", execution + "/explanation", execution + "/retry", execution + "/cancel",
                "/ai/reports/from-task/7")) {
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
        }
        verifyNoInteractions(agent, reports, rate);

        when(agent.get(id)).thenReturn(AgentJson.object().put("executionId", id));
        AiReportVO report = new AiReportVO();
        report.setSummary("保存的报告");
        when(reports.getSavedFromTask(7L)).thenReturn(report);
        mvc.perform(get(execution).requestAttr(AgentBoundary.REQUEST_ID, UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.data.executionId").value(id));
        mvc.perform(get("/ai/reports/from-task/7")).andExpect(jsonPath("$.data.summary").value("保存的报告"));
    }

}
