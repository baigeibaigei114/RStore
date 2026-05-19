package com.remotesensing.platform.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.dto.LayerSearchDTO;
import com.remotesensing.platform.service.LayerService;
import com.remotesensing.platform.vo.LayerVO;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(LayerController.class)
@Import(TestConfig.class)
class LayerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LayerService layerService;

    @Test
    @DisplayName("分页查询可访问图层列表")
    void pageLayersShouldReturnSuccess() throws Exception {
        LayerVO layer = new LayerVO();
        layer.setId(12L);
        layer.setTaskId(5L);
        layer.setImageId(3L);
        layer.setImageName("上海市黄浦区影像");
        layer.setTaskType("NDVI");
        layer.setTaskName("NDVI 处理任务");
        layer.setFileName("task_5.tif");
        layer.setFileType("GEOTIFF");
        layer.setOwnerId("user-a");
        layer.setVisibility("PRIVATE");
        layer.setWorkspace("remote_sensing");
        layer.setStoreName("task_5_ndvi_store");
        layer.setLayerName("task_5_ndvi");
        layer.setQualifiedLayerName("remote_sensing:task_5_ndvi");
        layer.setProxyWmsUrl("/api/layers/12/wms");
        layer.setProxyWcsUrl("/api/layers/12/wcs");
        layer.setPublishedAt(OffsetDateTime.parse("2026-05-19T10:00:00+08:00"));
        when(layerService.page(any(LayerSearchDTO.class), eq(1), eq(10)))
                .thenReturn(new PageResult<>(List.of(layer), 1, 1, 10));

        mockMvc.perform(get("/api/layers")
                        .contextPath("/api")
                        .param("pageNum", "1")
                        .param("pageSize", "10")
                        .param("taskType", "NDVI")
                        .param("imageId", "3")
                        .param("keyword", "黄浦"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].qualifiedLayerName").value("remote_sensing:task_5_ndvi"))
                .andExpect(jsonPath("$.data.records[0].proxyWmsUrl").value("/api/layers/12/wms"))
                .andExpect(jsonPath("$.data.records[0].proxyWcsUrl").value("/api/layers/12/wcs"));

        verify(layerService).page(any(LayerSearchDTO.class), eq(1), eq(10));
    }

    @Test
    @DisplayName("WMS 代理接口返回 GeoServer 图片响应")
    void proxyWmsShouldReturnBinaryResponse() throws Exception {
        when(layerService.proxyWms(eq(12L), any()))
                .thenReturn(ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_PNG)
                        .body(new byte[]{1, 2, 3}));

        mockMvc.perform(get("/api/layers/{id}/wms", 12L)
                        .contextPath("/api")
                        .param("request", "GetMap")
                        .param("layers", "other:layer"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.IMAGE_PNG))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(new byte[]{1, 2, 3}));

        verify(layerService).proxyWms(eq(12L), any());
    }

    @Test
    @DisplayName("WCS 代理接口返回 GeoServer 结果响应")
    void proxyWcsShouldReturnBinaryResponse() throws Exception {
        when(layerService.proxyWcs(eq(12L), any()))
                .thenReturn(ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(new byte[]{4, 5, 6}));

        mockMvc.perform(get("/api/layers/{id}/wcs", 12L)
                        .contextPath("/api")
                        .param("request", "GetCoverage")
                        .param("coverageId", "other:coverage"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(new byte[]{4, 5, 6}));

        verify(layerService).proxyWcs(eq(12L), any());
    }
}
