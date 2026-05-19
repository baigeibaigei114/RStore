package com.remotesensing.platform.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.dto.RsTaskStatusUpdateDTO;
import com.remotesensing.platform.service.RsTaskService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.RsResultFileVO;
import com.remotesensing.platform.vo.RsTaskClaimVO;
import com.remotesensing.platform.vo.RsTaskListVO;
import com.remotesensing.platform.vo.RsTaskLogVO;
import com.remotesensing.platform.vo.RsTaskVO;
import java.util.List;
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

    @Test
    @DisplayName("查询任务详情成功")
    void getTaskDetailShouldReturnSuccess() throws Exception {
        RsTaskVO task = new RsTaskVO();
        task.setId(1L);
        task.setStatus("SUCCESS");
        task.setProgress(100);
        task.setInputObjectKey("raw/2026/05/source.tif");
        task.setOutputObjectKey("result/NDVI/2026/05/task_1.tif");
        when(taskService.getById(1L)).thenReturn(task);

        mockMvc.perform(get("/api/tasks/{taskId}", 1L)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.outputObjectKey").value("result/NDVI/2026/05/task_1.tif"));

        verify(taskService).getById(1L);
    }

    @Test
    @DisplayName("分页查询任务列表成功")
    void pageTasksShouldReturnSuccess() throws Exception {
        RsTaskListVO task = new RsTaskListVO();
        task.setId(1L);
        task.setStatus("RUNNING");
        task.setProgress(30);
        when(taskService.page(1, 10)).thenReturn(new PageResult<>(List.of(task), 1, 1, 10));

        mockMvc.perform(get("/api/tasks")
                        .contextPath("/api")
                        .param("pageNum", "1")
                        .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].status").value("RUNNING"));

        verify(taskService).page(1, 10);
    }

    @Test
    @DisplayName("查询任务结果文件成功")
    void getTaskResultShouldReturnSuccess() throws Exception {
        RsResultFileVO resultFile = new RsResultFileVO();
        resultFile.setId(10L);
        resultFile.setTaskId(1L);
        resultFile.setObjectKey("result/NDVI/2026/05/task_1.tif");
        resultFile.setStatus("PUBLISHED");
        resultFile.setLayerName("rs_task_1");
        resultFile.setWmsUrl("http://localhost:8081/geoserver/remote_sensing/wms?layers=remote_sensing:rs_task_1");
        when(taskService.getResultFile(1L)).thenReturn(resultFile);

        mockMvc.perform(get("/api/tasks/{taskId}/result", 1L)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.objectKey").value("result/NDVI/2026/05/task_1.tif"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.layerName").value("rs_task_1"));

        verify(taskService).getResultFile(1L);
    }

    @Test
    @DisplayName("获取任务结果下载 URL 成功")
    void getTaskResultDownloadUrlShouldReturnSuccess() throws Exception {
        when(taskService.getResultDownloadUrl(1L))
                .thenReturn(new FilePresignedUrlVO("result/NDVI/2026/05/task_1.tif", "http://minio/result", 1800));

        mockMvc.perform(get("/api/tasks/{taskId}/result/download-url", 1L)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.objectKey").value("result/NDVI/2026/05/task_1.tif"))
                .andExpect(jsonPath("$.data.url").value("http://minio/result"));

        verify(taskService).getResultDownloadUrl(1L);
    }

    @Test
    @DisplayName("查询任务日志成功")
    void listTaskLogsShouldReturnSuccess() throws Exception {
        RsTaskLogVO log = new RsTaskLogVO();
        log.setId(1L);
        log.setTaskId(1L);
        log.setLogLevel("INFO");
        log.setMessage("任务已开始");
        when(taskService.listLogs(1L)).thenReturn(List.of(log));

        mockMvc.perform(get("/api/tasks/{taskId}/logs", 1L)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data[0].message").value("任务已开始"));

        verify(taskService).listLogs(1L);
    }

    /**
     * 场景：Worker 消费消息前先抢占任务，只有抢占成功才继续计算。
     */
    @Test
    @DisplayName("Worker 抢占任务成功")
    void claimTaskShouldReturnSuccess() throws Exception {
        when(taskService.claim(1L)).thenReturn(new RsTaskClaimVO(true, "CLAIMED", "RUNNING", "任务抢占成功", "result/NDVI/2026/05/task_1.tif"));

        mockMvc.perform(post("/api/tasks/{taskId}/claim", 1L)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.claimed").value(true))
                .andExpect(jsonPath("$.data.action").value("CLAIMED"));

        verify(taskService).claim(1L);
    }

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
