package com.remotesensing.platform.config;

import com.remotesensing.platform.config.properties.MinioProperties;
import com.remotesensing.platform.config.properties.PythonWorkerProperties;
import com.remotesensing.platform.config.properties.UploadProperties;
import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储配置类。
 * <p>
 * 职责：
 * - 启用 MinioProperties、PythonWorkerProperties、UploadProperties 配置绑定。
 * - 创建 MinioClient 单例 Bean，业务层通过依赖注入使用，避免泄露 accessKey/secretKey。
 */
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
