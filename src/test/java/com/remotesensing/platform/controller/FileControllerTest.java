package com.remotesensing.platform.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.config.TestConfig;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.service.RateLimitService;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@WebMvcTest(FileController.class)
@Import(TestConfig.class)
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private MinioService minioService;

    @BeforeEach
    void setUp() {
        reset(rateLimitService);
        clearInvocations(minioService);
    }

    @Test
    @DisplayName("通用预签名 URL 接口保持可用")
    void generatePresignedUrlShouldReturnSuccess() throws Exception {
        mockMvc.perform(get("/api/files/presigned-url")
                        .contextPath("/api")
                        .param("objectKey", "raw/2026/05/source.tif"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.SUCCESS.getCode()))
                .andExpect(jsonPath("$.data.objectKey").value("raw/2026/05/source.tif"))
                .andExpect(jsonPath("$.data.expireSeconds").value(3600));
    }

    @Test
    @DisplayName("通用预签名 URL 超过限流时不调用 MinIO")
    void generatePresignedUrlShouldRejectWhenRateLimited() throws Exception {
        doThrow(new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(), ResultCode.TOO_MANY_REQUESTS.getMessage()))
                .when(rateLimitService).check(eq("presigned-url:user:dev-user"), eq(60), eq(Duration.ofSeconds(60)));

        mockMvc.perform(get("/api/files/presigned-url")
                        .contextPath("/api")
                        .param("objectKey", "raw/2026/05/source.tif"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.TOO_MANY_REQUESTS.getCode()));

        verify(minioService, never()).generatePresignedUrl("raw/2026/05/source.tif");
    }
}
