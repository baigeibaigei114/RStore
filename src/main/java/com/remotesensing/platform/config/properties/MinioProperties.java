package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MinIO 对象存储配置属性，前缀为 "minio"。
 * <p>
 * MinIO 服务端地址、凭据和默认 bucket 均来自配置文件，便于本地和部署环境切换。
 */
@Data
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    /** MinIO 服务端 endpoint（内部访问地址，如 http://minio:9000）。 */
    private String endpoint;

    /** MinIO 公网 endpoint（用于生成预签名 URL 时返回给客户端的外部可访问地址）。 */
    private String publicEndpoint;

    /** MinIO 区域，默认 us-east-1。 */
    private String region = "us-east-1";

    /** MinIO 访问密钥（AK）。 */
    private String accessKey;

    /** MinIO 秘密密钥（SK）。 */
    private String secretKey;

    /** 默认存储桶名称，用于上传影像和结果文件。 */
    private String bucketName;
}
