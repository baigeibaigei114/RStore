package com.remotesensing.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    /**
     * MinIO 服务端地址、凭据和默认 bucket 均来自配置文件，便于本地和部署环境切换。
     */
    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String bucketName;
}
