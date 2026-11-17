package com.remotesensing.platform.resultanalysis;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.remotesensing.platform.agent.AgentAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TaskResultAnswerControllerTest {
    @Test void retiredResultAnswerPostCannotReachHistoryOrEvidenceService() throws Exception {
        var service=mock(TaskResultAnswerService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new TaskResultAnswerController(service))
                .setControllerAdvice(new AgentAdvice()).build();
        for (String body : new String[]{"{}", "{\"question\":\"解释当前结果\"}"}) {
            mvc.perform(post("/agent/task-results/7/answers").contentType("application/json").content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.data.errorCode").value("RESULT_ANSWERS_READ_ONLY"));
        }
        verifyNoInteractions(service);
    }
}
