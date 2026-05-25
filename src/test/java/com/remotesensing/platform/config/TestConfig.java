package com.remotesensing.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.properties.AuthProperties;
import com.remotesensing.platform.config.properties.RateLimitProperties;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.service.JwtTokenService;
import com.remotesensing.platform.service.RateLimitService;
import com.remotesensing.platform.service.TokenBlacklistService;
import com.remotesensing.platform.service.impl.JwtTokenServiceImpl;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.MinioUploadVO;
import io.minio.MinioClient;
import org.mockito.Mockito;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

@TestConfiguration
@Profile("test")
@EnableConfigurationProperties({AuthProperties.class, RateLimitProperties.class})
public class TestConfig {

    @Bean
    @Primary
    public JwtTokenService testJwtTokenService(AuthProperties authProperties, ObjectMapper objectMapper) {
        return new JwtTokenServiceImpl(authProperties, objectMapper);
    }

    @Bean
    @Primary
    public TokenBlacklistService mockTokenBlacklistService() {
        return Mockito.mock(TokenBlacklistService.class);
    }

    @Bean
    @Primary
    public RateLimitService mockRateLimitService() {
        return Mockito.mock(RateLimitService.class);
    }

    @Bean
    @Primary
    public CurrentUserContext testCurrentUserContext(JwtTokenService jwtTokenService,
                                                     AuthProperties authProperties,
                                                     TokenBlacklistService tokenBlacklistService) {
        return new CurrentUserContext(jwtTokenService, authProperties, tokenBlacklistService);
    }

    @Bean
    @Primary
    public MinioClient mockMinioClient() {
        return Mockito.mock(MinioClient.class);
    }

    @Bean
    @Primary
    public MinioService mockMinioService() {
        MinioService minioService = Mockito.mock(MinioService.class);
        Mockito.when(minioService.uploadGeoTiff(Mockito.any(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(new MinioUploadVO(
                        "test-remote-sensing-images",
                        "test/geotiff/mock-image.tif",
                        1024L,
                        "image/tiff"
                ));
        Mockito.when(minioService.uploadLocalFile(Mockito.any(), Mockito.anyString(), Mockito.anyString()))
                .thenAnswer(invocation -> new MinioUploadVO(
                        "test-remote-sensing-images",
                        invocation.getArgument(1, String.class),
                        512L,
                        invocation.getArgument(2, String.class)
                ));
        Mockito.when(minioService.generatePresignedUrl(Mockito.anyString()))
                .thenAnswer(invocation -> {
                    String objectKey = invocation.getArgument(0, String.class);
                    return new FilePresignedUrlVO(
                            objectKey,
                            "http://127.0.0.1:19000/test-remote-sensing-images/" + objectKey,
                            3600
                    );
                });
        return minioService;
    }

    @Bean
    @Primary
    public MockRabbitMqClient mockRabbitMqClient() {
        return new MockRabbitMqClient();
    }

    public static class MockRabbitMqClient {

        public void publish(String exchange, String routingKey, Object payload) {
            // No-op mock for tests that should not connect to RabbitMQ.
        }
    }
}
