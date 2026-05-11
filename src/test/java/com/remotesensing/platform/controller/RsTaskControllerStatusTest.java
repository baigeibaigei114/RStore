package com.remotesensing.platform.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.dto.RsTaskStatusUpdateDTO;
import com.remotesensing.platform.service.RsTaskService;
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
@WebMvcTest(RsTaskController.class)
@Import(TestConfig.class)
class RsTaskControllerStatusTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RsTaskService taskService;

    /**
     * 场景：Worker 上报运行中进度时，Controller 应接收请求并交给 Service 校验流转规则。
     */
    @Test
    @DisplayName("Worker 回调 RUNNING 状态成功")
    void updateRunningStatusShouldReturnSuccess() throws Exception {
        mockMvc.perform(post("/api/tasks/{taskId}/status", 1L)
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "status": "RUNNING",
                                  "progress": 30,
                                  "message": "已读取波段数据"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()));

        verify(taskService).updateStatus(eq(1L), any(RsTaskStatusUpdateDTO.class));
    }

    /**
     * 场景：缺少 status 时请求无效，不能进入任务状态更新流程。
     */
    @Test
    @DisplayName("Worker 回调缺少状态失败")
    void updateStatusWithoutStatusShouldNotCallService() throws Exception {
        mockMvc.perform(post("/api/tasks/{taskId}/status", 1L)
                        .contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "progress": 30,
                                  "message": "已读取波段数据"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));

        verify(taskService, never()).updateStatus(any(), any());
    }
}
