package com.remotesensing.platform.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.RateLimitService;
import com.remotesensing.platform.service.RsImageService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.RsImageVO;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

@ActiveProfiles("test")
@WebMvcTest(RsImageController.class)
@Import(TestConfig.class)
class RsImageControllerUploadTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RsImageService imageService;

    @Autowired
    private RateLimitService rateLimitService;

    @AfterEach
    void tearDown() {
        reset(rateLimitService);
    }

    /**
     * 测试类：RsImageController；测试方法：upload。
     * 场景：上传正确 GeoTIFF 文件时，Controller 应调用 Service 并返回统一成功 JSON。
     */
    @Test
    @DisplayName("上传正确 GeoTIFF 文件成功")
    void uploadGeoTiffFileShouldReturnSuccess() throws Exception {
        RsImageVO imageVO = buildMockImageVO();
        when(imageService.upload(any(MultipartFile.class), eq("测试影像"), eq("GF-2"), isNull(), isNull()))
                .thenReturn(imageVO);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.tif",
                "image/tiff",
                "mock geotiff bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/images/upload")
                        .file(file)
                        .param("name", "测试影像")
                        .param("sensor", "GF-2")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.message").value(ResultCode.SUCCESS.getMessage()))
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.imageName").value("测试影像"))
                .andExpect(jsonPath("$.data.objectKey").value("test/geotiff/sample.tif"))
                .andExpect(jsonPath("$.data.minioBucket").value("test-remote-sensing-images"));

        verify(imageService).upload(any(MultipartFile.class), eq("测试影像"), eq("GF-2"), isNull(), isNull());
    }

    /**
     * 测试类：RsImageController；测试方法：upload。
     * 场景：上传非 .tif/.tiff 文件时，mock Service 返回参数错误，Controller 应输出统一失败 JSON。
     */
    @Test
    @DisplayName("上传非 tif 文件失败")
    void uploadShouldRejectWhenRateLimited() throws Exception {
        doThrow(new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(), ResultCode.TOO_MANY_REQUESTS.getMessage()))
                .when(rateLimitService).check(eq("upload:user:dev-user"), eq(5), eq(Duration.ofSeconds(300)));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.tif",
                "image/tiff",
                "mock geotiff bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/images/upload")
                        .file(file)
                        .param("name", "限流影像")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.TOO_MANY_REQUESTS.getCode()));

        verify(imageService, never()).upload(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("涓婁紶闈?tif 鏂囦欢澶辫触")
    void uploadNonTifFileShouldReturnParamError() throws Exception {
        when(imageService.upload(any(MultipartFile.class), eq("错误格式影像"), isNull(), isNull(), isNull()))
                .thenThrow(new BusinessException(
                        ResultCode.PARAM_ERROR.getCode(),
                        "只允许上传 .tif 或 .tiff 格式的 GeoTIFF 文件"
                ));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.jpg",
                "image/jpeg",
                "not a geotiff".getBytes()
        );

        mockMvc.perform(multipart("/api/images/upload")
                        .file(file)
                        .param("name", "错误格式影像")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("只允许上传 .tif 或 .tiff 格式的 GeoTIFF 文件"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(imageService).upload(any(MultipartFile.class), eq("错误格式影像"), isNull(), isNull(), isNull());
    }

    /**
     * 测试类：RsImageController；测试方法：upload。
     * 场景：上传空文件时，mock Service 返回参数错误，Controller 应输出统一失败 JSON。
     */
    @Test
    @DisplayName("上传空文件失败")
    void uploadEmptyFileShouldReturnParamError() throws Exception {
        when(imageService.upload(any(MultipartFile.class), eq("空文件影像"), isNull(), isNull(), isNull()))
                .thenThrow(new BusinessException(ResultCode.PARAM_ERROR.getCode(), "上传文件不能为空"));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "empty.tif",
                "image/tiff",
                new byte[0]
        );

        mockMvc.perform(multipart("/api/images/upload")
                        .file(file)
                        .param("name", "空文件影像")
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value("上传文件不能为空"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(imageService).upload(any(MultipartFile.class), eq("空文件影像"), isNull(), isNull(), isNull());
    }

    /**
     * 测试类：RsImageController；测试方法：upload。
     * 场景：缺少 name 参数时，Controller 不应进入 Service，直接由 Spring MVC 参数校验返回错误。
     */
    @Test
    @DisplayName("缺少影像名称参数失败")
    void uploadWithoutNameShouldNotCallService() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.tif",
                "image/tiff",
                "mock geotiff bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/images/upload")
                        .file(file)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()));

        verify(imageService, never()).upload(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("获取原始影像下载 URL 成功")
    void getDownloadUrlShouldReturnSuccess() throws Exception {
        when(imageService.getDownloadUrl(1L))
                .thenReturn(new FilePresignedUrlVO("raw/2026/05/source.tif", "http://minio/raw", 1800));

        mockMvc.perform(get("/api/images/{id}/download-url", 1L)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.objectKey").value("raw/2026/05/source.tif"))
                .andExpect(jsonPath("$.data.url").value("http://minio/raw"));

        verify(imageService).getDownloadUrl(1L);
    }

    @Test
    @DisplayName("获取缩略图 URL 成功")
    void getThumbnailUrlShouldReturnSuccess() throws Exception {
        when(imageService.getThumbnailUrl(1L))
                .thenReturn(new FilePresignedUrlVO("thumbnail/2026/05/1.png", "http://minio/thumbnail", 1800));

        mockMvc.perform(get("/api/images/{id}/thumbnail-url", 1L)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.objectKey").value("thumbnail/2026/05/1.png"))
                .andExpect(jsonPath("$.data.url").value("http://minio/thumbnail"));

        verify(imageService).getThumbnailUrl(1L);
    }

    @Test
    @DisplayName("原始影像下载 URL 超过限流时不调用 ImageService")
    void getDownloadUrlShouldRejectWhenRateLimited() throws Exception {
        doThrow(new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(), ResultCode.TOO_MANY_REQUESTS.getMessage()))
                .when(rateLimitService).check(eq("presigned-url:user:dev-user"), eq(60), eq(Duration.ofSeconds(60)));

        mockMvc.perform(get("/api/images/{id}/download-url", 1L)
                        .contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.TOO_MANY_REQUESTS.getCode()));

        verify(imageService, never()).getDownloadUrl(any());
    }

    private RsImageVO buildMockImageVO() {
        RsImageVO imageVO = new RsImageVO();
        imageVO.setId(1L);
        imageVO.setImageCode("RS-20260508-000001");
        imageVO.setImageName("测试影像");
        imageVO.setSensorType("GF-2");
        imageVO.setCloudPercent(new BigDecimal("12.5"));
        imageVO.setAcquisitionTime(OffsetDateTime.parse("2026-05-08T10:00:00+08:00"));
        imageVO.setFileFormat("GeoTIFF");
        imageVO.setFileSize(1024L);
        imageVO.setContentType("image/tiff");
        imageVO.setMinioBucket("test-remote-sensing-images");
        imageVO.setObjectKey("test/geotiff/sample.tif");
        return imageVO;
    }
}
