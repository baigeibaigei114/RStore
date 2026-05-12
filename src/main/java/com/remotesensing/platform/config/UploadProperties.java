package com.remotesensing.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "upload")
public class UploadProperties {

    /**
     * 大文件上传会同时占用磁盘 IO、Web 线程和 Python 进程，本地开发默认保守限制。
     */
    private int maxConcurrent = 2;

    /**
     * 缩略图异步生成会读取 GeoTIFF 并启动 Python 进程，线程池默认保持较小规模。
     */
    private int thumbnailCorePoolSize = 1;
    private int thumbnailMaxPoolSize = 2;
    private int thumbnailQueueCapacity = 20;
    private int thumbnailRetryBatchSize = 20;
    private long thumbnailRetryFixedDelayMs = 60000;
}
