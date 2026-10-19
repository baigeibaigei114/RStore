package com.remotesensing.platform.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.service.RateLimitService;
import com.remotesensing.platform.service.RsTaskService;
import com.remotesensing.platform.vo.RsTaskSubmitVO;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(RsTaskController.class)
@Import(TestConfig.class)
class RsTaskSubmitAutomationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RsTaskService taskService;

    @Autowired
    private RateLimitService rateLimitService;

    @AfterEach
    void tearDown() {
        reset(rateLimitService);
    }

    @Test
    @DisplayName("自动化测试：提交 NDVI 任务时正确解析请求并返回任务 ID")
    void submitNdviTaskShouldParseRequestAndReturnTaskId() throws Exception {
        when(taskService.submit(any(RsTaskSubmitDTO.class))).thenReturn(new RsTaskSubmitVO(2001L));

        mockMvc.perform(post("/api/tasks")
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "imageId": 1001,
                                  "taskType": "NDVI",
                                  "clientRequestId": "web-ndvi-1001-001",
                                  "params": {
                                    "redBand": 3,
                                    "nirBand": 4
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.taskId").value(2001L));

        ArgumentCaptor<RsTaskSubmitDTO> dtoCaptor = ArgumentCaptor.forClass(RsTaskSubmitDTO.class);
        verify(taskService).submit(dtoCaptor.capture());
        verify(rateLimitService).check("task-submit:user:dev-user", 10, Duration.ofSeconds(60));

        RsTaskSubmitDTO submitDTO = dtoCaptor.getValue();
        assertThat(submitDTO.getImageId()).isEqualTo(1001L);
        assertThat(submitDTO.getTaskType()).isEqualTo(TaskType.NDVI);
        assertThat(submitDTO.getClientRequestId()).isEqualTo("web-ndvi-1001-001");
        assertThat(submitDTO.getParams())
                .containsEntry("redBand", 3)
                .containsEntry("nirBand", 4);
    }
}
