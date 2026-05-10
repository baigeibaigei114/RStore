package com.remotesensing.platform.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MinioProperties.class, PythonWorkerProperties.class, UploadProperties.class})
public class MinioConfig {

    /**
     * MinIO 客户端只集中在配置层创建，业务层通过注入使用，避免泄露 accessKey/secretKey。
     */
    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }
}
