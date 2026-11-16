package com.remotesensing.platform.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.agent.AgentController;
import com.remotesensing.platform.agent.AgentProperties;
import com.remotesensing.platform.agent.AgentService;
import com.remotesensing.platform.agent.AnalysisPreparation;
import com.remotesensing.platform.agent.ImagingAssistantController;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.entity.SysUser;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.service.JwtTokenService;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.service.PublicImageService;
import com.remotesensing.platform.service.RateLimitService;
import com.remotesensing.platform.service.RsImageService;
import com.remotesensing.platform.service.RsTaskService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.RsTaskSubmitVO;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest({PublicImageController.class, RsImageController.class, RsTaskController.class,
        ImagingAssistantController.class, AgentController.class})
@Import({TestConfig.class, PublicImageService.class, AgentProperties.class})
@TestPropertySource(properties = "app.auth.dev-user-header-enabled=false")
class PublicImageControllerTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtTokenService jwt;
    @Autowired private MinioService minio;
    @Autowired private RateLimitService rateLimit;
    @MockBean private RsImageMapper mapper;
    @MockBean private RsImageService imageService;
    @MockBean private RsTaskService taskService;
    @MockBean private AnalysisPreparation preparation;
    @MockBean private AgentService agent;

    @AfterEach
    void resetTestBeans() {
        reset(minio, rateLimit);
    }

    @Test
    void publicListSharesTheSameNormalizedFilterWithTheCount() throws Exception {
        when(mapper.selectPublicPage("Sentinel", 10L, 10)).thenReturn(List.of(image()));
        when(mapper.countPublic("Sentinel")).thenReturn(11L);
        mvc.perform(get("/api/public/images").contextPath("/api").header("X-User-Id", "someone")
                        .param("pageNum", "2").param("keyword", " Sentinel "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.records[0].imageName").value("公开样本"))
                .andExpect(jsonPath("$.data.records[0].ownerId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].objectKey").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].thumbnailObjectKey").doesNotExist());
        verifyNoInteractions(minio);
    }

    @Test
    void detailSignsOnlyByImageIdAndDoesNotCacheOrExposeStorageFields() throws Exception {
        when(mapper.selectPublicById(3L)).thenReturn(image());
        when(minio.generatePublicThumbnailUrl(3L))
                .thenReturn(new FilePresignedUrlVO("thumbnail/3.webp", "http://preview.test/image.webp", 300));

        mvc.perform(get("/api/public/images/3").contextPath("/api")
                        .header("Authorization", "Bearer ignored-by-public-read"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.data.thumbnailPreview.url").value("http://preview.test/image.webp"))
                .andExpect(jsonPath("$.data.thumbnailPreview.expiresAt").isString())
                .andExpect(jsonPath("$.data.metadataJson").doesNotExist())
                .andExpect(jsonPath("$.data.thumbnailObjectKey").doesNotExist())
                .andExpect(jsonPath("$.data.minioBucket").doesNotExist());
        verify(rateLimit).check("public-image-preview:ip:127.0.0.1", 120, Duration.ofMinutes(1));
    }

    @Test
    void aMissingRecordDoesNotAttemptToSign() throws Exception {
        mvc.perform(get("/api/public/images/8").contextPath("/api"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("未找到影像"));
        verifyNoInteractions(minio, rateLimit);
    }

    @Test
    void detailRemainsAvailableWithoutAThumbnailOrWhenSignatureIsRateLimited() throws Exception {
        RsImage withoutThumbnail = image();
        withoutThumbnail.setThumbnailObjectKey(null);
        when(mapper.selectPublicById(3L)).thenReturn(withoutThumbnail, image());
        doThrow(new BusinessException(429, "rate limited")).when(rateLimit)
                .check("public-image-preview:ip:127.0.0.1", 120, Duration.ofMinutes(1));

        mvc.perform(get("/api/public/images/3").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewAvailable").value(false));
        mvc.perform(get("/api/public/images/3").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewAvailable").value(true))
                .andExpect(jsonPath("$.data.thumbnailPreview").doesNotExist());
        verifyNoInteractions(minio);
    }

    @Test
    void onlyPublicGetMethodsAreAnonymousAndHeaderCannotAuthorizeActions() throws Exception {
        mvc.perform(head("/api/public/images/3").contextPath("/api"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/images/3/download-url").contextPath("/api"))
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(get("/api/images/3/download-url").contextPath("/api").header("X-User-Id", "1"))
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(multipart("/api/images/upload").contextPath("/api").header("X-User-Id", "1"))
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(post("/api/tasks").contextPath("/api").header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageId\":3,\"taskType\":\"NDVI\"}"))
                .andExpect(jsonPath("$.code").value(401));
        for (String endpoint : List.of("/api/agent/imaging-assistant/preparation", "/api/agent/executions/1/confirm")) {
            mvc.perform(post(endpoint).contextPath("/api").header("X-User-Id", "1")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }
        verifyNoInteractions(mapper, minio, imageService, taskService, preparation, agent);
    }

    @Test
    void existingTaskSubmissionStillReachesTheServiceWithAJwt() throws Exception {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("fixture");
        user.setRole("USER");
        when(taskService.submit(any())).thenReturn(new RsTaskSubmitVO(71L));
        mvc.perform(post("/api/tasks").contextPath("/api").header("Authorization", "Bearer " + jwt.generateToken(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"imageId\":3,\"taskType\":\"NDVI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(71));
        verify(taskService).submit(any());
    }

    private static RsImage image() {
        RsImage image = new RsImage();
        image.setId(3L);
        image.setImageName("公开样本");
        image.setStatus("READY");
        image.setOwnerId("1");
        image.setObjectKey("raw/3.tif");
        image.setThumbnailObjectKey("thumbnail/3.webp");
        image.setMinioBucket("private-bucket");
        image.setMetadataJson("{}");
        return image;
    }
}
