package com.remotesensing.platform.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.service.GeoServerService;
import com.remotesensing.platform.vo.GeoServerPublishVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(GeoServerController.class)
@Import(TestConfig.class)
class GeoServerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GeoServerService geoServerService;

    @Test
    @DisplayName("发布任务结果为 GeoServer 图层成功")
    void publishTaskResultShouldReturnSuccess() throws Exception {
        GeoServerPublishVO publishVO = new GeoServerPublishVO();
        publishVO.setTaskId(1L);
        publishVO.setWorkspace("remote_sensing");
        publishVO.setStoreName("task_1_ndvi_store");
        publishVO.setLayerName("task_1_ndvi");
        publishVO.setQualifiedLayerName("remote_sensing:task_1_ndvi");
        publishVO.setSourceObjectKey("result/NDVI/2026/05/task_1.tif");
        when(geoServerService.publishTaskResult(1L)).thenReturn(publishVO);

        mockMvc.perform(post("/api/geoserver/publish/{taskId}", 1L)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.qualifiedLayerName").value("remote_sensing:task_1_ndvi"))
                .andExpect(jsonPath("$.data.sourceObjectKey").value("result/NDVI/2026/05/task_1.tif"));

        verify(geoServerService).publishTaskResult(1L);
    }
}
