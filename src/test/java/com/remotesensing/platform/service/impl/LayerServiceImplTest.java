package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.enums.Visibility;
import com.remotesensing.platform.dto.LayerSearchDTO;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.LayerMapper;
import com.remotesensing.platform.service.GeoServerProxyClient;
import com.remotesensing.platform.vo.LayerVO;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class LayerServiceImplTest {

    @Mock
    private LayerMapper layerMapper;

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private GeoServerProxyClient geoServerProxyClient;

    private LayerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LayerServiceImpl(layerMapper, currentUserContext, geoServerProxyClient);
    }

    @Test
    @DisplayName("用户可以查询自己 PRIVATE 的已发布图层")
    void pageShouldReturnOwnerPrivatePublishedLayer() {
        LayerSearchDTO query = new LayerSearchDTO();
        LayerVO layer = layer(12L, "user-a", Visibility.PRIVATE.dbValue(), "remote_sensing", "task_5_ndvi");

        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(layerMapper.selectPage(query, 0, 10, "user-a")).thenReturn(List.of(layer));
        when(layerMapper.count(query, "user-a")).thenReturn(1L);

        PageResult<LayerVO> result = service.page(query, 1, 10);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getRecords().get(0).getQualifiedLayerName()).isEqualTo("remote_sensing:task_5_ndvi");
        assertThat(result.getRecords().get(0).getProxyWmsUrl()).isEqualTo("/api/layers/12/wms");
        assertThat(result.getRecords().get(0).getProxyWcsUrl()).isEqualTo("/api/layers/12/wcs");
    }

    @Test
    @DisplayName("用户 B 查询时使用自己的用户标识，不能看到用户 A 的 PRIVATE 图层")
    void pageShouldUseCurrentUserForPermissionFilter() {
        LayerSearchDTO query = new LayerSearchDTO();

        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(layerMapper.selectPage(query, 0, 10, "user-b")).thenReturn(List.of());
        when(layerMapper.count(query, "user-b")).thenReturn(0L);

        PageResult<LayerVO> result = service.page(query, 1, 10);

        assertThat(result.getRecords()).isEmpty();
        verify(layerMapper).selectPage(query, 0, 10, "user-b");
    }

    @Test
    @DisplayName("用户 B 可以看到用户 A 的 PUBLIC 图层")
    void pageShouldReturnPublicLayerForOtherUser() {
        LayerSearchDTO query = new LayerSearchDTO();
        LayerVO layer = layer(13L, "user-a", Visibility.PUBLIC.dbValue(), "remote_sensing", "task_6_ndwi");

        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(layerMapper.selectPage(query, 0, 10, "user-b")).thenReturn(List.of(layer));
        when(layerMapper.count(query, "user-b")).thenReturn(1L);

        PageResult<LayerVO> result = service.page(query, 1, 10);

        assertThat(result.getRecords().get(0).getVisibility()).isEqualTo(Visibility.PUBLIC.dbValue());
        assertThat(result.getRecords().get(0).getQualifiedLayerName()).isEqualTo("remote_sensing:task_6_ndwi");
    }

    @Test
    @DisplayName("分页参数和筛选条件应传递给 Mapper")
    void pageShouldPassPaginationAndFiltersToMapper() {
        LayerSearchDTO query = new LayerSearchDTO();
        query.setTaskType(" NDVI ");
        query.setImageId(3L);
        query.setKeyword(" 黄浦 ");

        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(layerMapper.selectPage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq("user-a")))
                .thenReturn(List.of());
        when(layerMapper.count(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("user-a"))).thenReturn(0L);

        service.page(query, 2, 10);

        ArgumentCaptor<LayerSearchDTO> captor = ArgumentCaptor.forClass(LayerSearchDTO.class);
        verify(layerMapper).selectPage(captor.capture(), org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq(10), org.mockito.ArgumentMatchers.eq("user-a"));
        assertThat(captor.getValue().getTaskType()).isEqualTo("NDVI");
        assertThat(captor.getValue().getImageId()).isEqualTo(3L);
        assertThat(captor.getValue().getKeyword()).isEqualTo("黄浦");
    }

    @Test
    @DisplayName("WMS 代理应强制覆盖 layers 参数并保留 GeoServer 响应")
    void proxyWmsShouldOverrideLayersAndReturnGeoServerResponse() {
        LayerVO layer = layer(12L, "user-a", Visibility.PRIVATE.dbValue(), "remote_sensing", "task_5_ndvi");
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("request", "GetMap");
        params.add("format", "image/png");
        params.add("sld_body", "<StyledLayerDescriptor/>");
        params.add("layers", "other:layer");
        ResponseEntity<StreamingResponseBody> response = ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(streaming(new byte[]{1, 2, 3}));

        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(layerMapper.selectAccessiblePublishedById(12L, "user-a")).thenReturn(layer);
        when(geoServerProxyClient.proxy(org.mockito.ArgumentMatchers.eq("/wms"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(response);

        ResponseEntity<StreamingResponseBody> result = service.proxyWms(12L, params);

        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(geoServerProxyClient).proxy(org.mockito.ArgumentMatchers.eq("/wms"), captor.capture());
        assertThat(captor.getValue().getFirst("service")).isEqualTo("WMS");
        assertThat(captor.getValue().getFirst("layers")).isEqualTo("remote_sensing:task_5_ndvi");
        assertThat(captor.getValue().getFirst("format")).isEqualTo("image/png");
        assertThat(captor.getValue()).doesNotContainKey("sld_body");
        assertThat(result.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(streamToBytes(result.getBody())).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("WCS 代理应强制覆盖 coverageId 参数")
    void proxyWcsShouldOverrideCoverageId() {
        LayerVO layer = layer(12L, "user-a", Visibility.PUBLIC.dbValue(), "remote_sensing", "task_5_ndvi");
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("request", "GetCoverage");
        params.add("format", "image/tiff");
        params.add("coverageId", "other:coverage");

        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(layerMapper.selectAccessiblePublishedById(12L, "user-b")).thenReturn(layer);
        when(geoServerProxyClient.proxy(org.mockito.ArgumentMatchers.eq("/wcs"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ResponseEntity.ok(streaming(new byte[]{4, 5})));

        service.proxyWcs(12L, params);

        ArgumentCaptor<MultiValueMap<String, String>> captor = ArgumentCaptor.forClass(MultiValueMap.class);
        verify(geoServerProxyClient).proxy(org.mockito.ArgumentMatchers.eq("/wcs"), captor.capture());
        assertThat(captor.getValue().getFirst("service")).isEqualTo("WCS");
        assertThat(captor.getValue().getFirst("coverageId")).isEqualTo("remote_sensing:task_5_ndvi");
    }

    @Test
    @DisplayName("无权访问或未发布图层不能代理访问")
    void proxyShouldRejectInaccessibleLayer() {
        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(layerMapper.selectAccessiblePublishedById(12L, "user-b")).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.proxyWms(12L, new LinkedMultiValueMap<>()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("图层不存在");
    }

    @Test
    @DisplayName("WMS 代理只允许 GetMap 请求")
    void proxyWmsShouldOnlyAllowGetMap() {
        LayerVO layer = layer(12L, "user-a", Visibility.PRIVATE.dbValue(), "remote_sensing", "task_5_ndvi");
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("request", "GetCapabilities");
        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(layerMapper.selectAccessiblePublishedById(12L, "user-a")).thenReturn(layer);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.proxyWms(12L, params))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("GetMap");
    }

    @Test
    @DisplayName("WMS 代理限制渲染尺寸")
    void proxyWmsShouldLimitRenderSize() {
        LayerVO layer = layer(12L, "user-a", Visibility.PRIVATE.dbValue(), "remote_sensing", "task_5_ndvi");
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("request", "GetMap");
        params.add("width", "4097");
        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(layerMapper.selectAccessiblePublishedById(12L, "user-a")).thenReturn(layer);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.proxyWms(12L, params))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("width");
    }

    @Test
    @DisplayName("图层查询 SQL 应过滤未发布或缺少图层标识的数据")
    void mapperXmlShouldFilterUnavailableLayers() throws Exception {
        String xml = new String(
                java.nio.file.Files.readAllBytes(java.nio.file.Path.of("src/main/resources/mapper/LayerMapper.xml")),
                java.nio.charset.StandardCharsets.UTF_8
        );

        assertThat(xml).contains("rf.status = 'PUBLISHED'");
        assertThat(xml).contains("rf.layer_name IS NOT NULL");
        assertThat(xml).contains("rf.workspace IS NOT NULL");
        assertThat(xml).contains("(rf.visibility = 'PUBLIC' OR rf.owner_id = #{currentUserId})");
        assertThat(xml).contains("selectAccessiblePublishedById");
        assertThat(xml).contains("i.deleted_at AS image_deleted_at");
    }

    private LayerVO layer(Long id, String ownerId, String visibility, String workspace, String layerName) {
        LayerVO layer = new LayerVO();
        layer.setId(id);
        layer.setTaskId(5L);
        layer.setImageId(3L);
        layer.setImageName("上海市黄浦区影像");
        layer.setTaskType("NDVI");
        layer.setTaskName("NDVI 处理任务");
        layer.setFileName("task_5.tif");
        layer.setFileType("GEOTIFF");
        layer.setOwnerId(ownerId);
        layer.setVisibility(visibility);
        layer.setWorkspace(workspace);
        layer.setStoreName("task_5_ndvi_store");
        layer.setLayerName(layerName);
        layer.setPublishedAt(OffsetDateTime.parse("2026-05-19T10:00:00+08:00"));
        return layer;
    }

    private StreamingResponseBody streaming(byte[] bytes) {
        return outputStream -> outputStream.write(bytes);
    }

    private byte[] streamToBytes(StreamingResponseBody body) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            body.writeTo(outputStream);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return outputStream.toByteArray();
    }
}
