package com.remotesensing.platform.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.AdminRegionService;
import com.remotesensing.platform.vo.AdminRegionDetailVO;
import com.remotesensing.platform.vo.AdminRegionVO;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(AdminRegionController.class)
@Import(TestConfig.class)
class AdminRegionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminRegionService adminRegionService;

    @Test
    @DisplayName("parentId 为空时查询顶级行政区")
    void listTopRegionsShouldReturnSuccess() throws Exception {
        AdminRegionVO region = regionVO(31L, "上海市", "province", null);
        when(adminRegionService.listChildren(null)).thenReturn(List.of(region));

        mockMvc.perform(get("/api/admin-regions/children")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data[0].id").value(31L))
                .andExpect(jsonPath("$.data[0].adcode").value("31"))
                .andExpect(jsonPath("$.data[0].name").value("上海市"))
                .andExpect(jsonPath("$.data[0].level").value("province"))
                .andExpect(jsonPath("$.data[0].parentId").doesNotExist())
                .andExpect(jsonPath("$.data[0].boundaryGeoJson").doesNotExist());

        verify(adminRegionService).listChildren(null);
    }

    @Test
    @DisplayName("parentId 不为空时查询直接下级行政区")
    void listChildRegionsShouldReturnSuccess() throws Exception {
        AdminRegionVO region = regionVO(310100L, "上海市辖区", "city", 31L);
        when(adminRegionService.listChildren(31L)).thenReturn(List.of(region));

        mockMvc.perform(get("/api/admin-regions/children")
                        .contextPath("/api")
                        .param("parentId", "31"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data[0].id").value(310100L))
                .andExpect(jsonPath("$.data[0].parentId").value(31L));

        verify(adminRegionService).listChildren(31L);
    }

    @Test
    @DisplayName("根据 level 查询行政区")
    void listByLevelShouldReturnSuccess() throws Exception {
        AdminRegionVO region = regionVO(31L, "上海市", "province", null);
        when(adminRegionService.listByLevel("province")).thenReturn(List.of(region));

        mockMvc.perform(get("/api/admin-regions")
                        .contextPath("/api")
                        .param("level", "province"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data[0].level").value("province"));

        verify(adminRegionService).listByLevel("province");
    }

    @Test
    @DisplayName("根据 id 查询行政区详情和边界")
    void getRegionDetailShouldReturnBoundaryGeoJson() throws Exception {
        AdminRegionDetailVO detail = new AdminRegionDetailVO();
        detail.setId(310101L);
        detail.setAdcode("310101");
        detail.setName("黄浦区");
        detail.setLevel("district");
        detail.setParentId(310100L);
        detail.setBoundaryGeoJson("{\"type\":\"MultiPolygon\",\"coordinates\":[]}");
        when(adminRegionService.getDetail(310101L, null)).thenReturn(detail);

        mockMvc.perform(get("/api/admin-regions/{id}", 310101L)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.id").value(310101L))
                .andExpect(jsonPath("$.data.adcode").value("310101"))
                .andExpect(jsonPath("$.data.boundaryGeoJson").value("{\"type\":\"MultiPolygon\",\"coordinates\":[]}"));

        verify(adminRegionService).getDetail(310101L, null);
    }

    @Test
    @DisplayName("根据 id 查询行政区详情时支持指定简化容差")
    void getRegionDetailShouldPassSimplifyTolerance() throws Exception {
        AdminRegionDetailVO detail = new AdminRegionDetailVO();
        detail.setId(310101L);
        detail.setAdcode("310101");
        detail.setName("黄浦区");
        detail.setLevel("district");
        detail.setParentId(310100L);
        detail.setBoundaryGeoJson("{\"type\":\"MultiPolygon\",\"coordinates\":[]}");
        when(adminRegionService.getDetail(310101L, 0.0D)).thenReturn(detail);

        mockMvc.perform(get("/api/admin-regions/{id}", 310101L)
                        .contextPath("/api")
                        .param("simplifyTolerance", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.id").value(310101L));

        verify(adminRegionService).getDetail(310101L, 0.0D);
    }

    @Test
    @DisplayName("行政区不存在时返回业务错误")
    void getMissingRegionShouldReturnBusinessError() throws Exception {
        when(adminRegionService.getDetail(999L, null))
                .thenThrow(new BusinessException(ResultCode.PARAM_ERROR.getCode(), "行政区不存在"));

        mockMvc.perform(get("/api/admin-regions/{id}", 999L)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("行政区不存在"));

        verify(adminRegionService).getDetail(999L, null);
    }

    @Test
    @DisplayName("按名称搜索行政区")
    void searchRegionShouldReturnSuccess() throws Exception {
        AdminRegionVO region = regionVO(310101L, "黄浦区", "district", 310100L);
        when(adminRegionService.search("黄浦")).thenReturn(List.of(region));

        mockMvc.perform(get("/api/admin-regions/search")
                        .contextPath("/api")
                        .param("keyword", "黄浦"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data[0].name").value("黄浦区"));

        verify(adminRegionService).search("黄浦");
    }

    private AdminRegionVO regionVO(Long id, String name, String level, Long parentId) {
        AdminRegionVO vo = new AdminRegionVO();
        vo.setId(id);
        vo.setAdcode(String.valueOf(id));
        vo.setName(name);
        vo.setLevel(level);
        vo.setParentId(parentId);
        return vo;
    }
}
