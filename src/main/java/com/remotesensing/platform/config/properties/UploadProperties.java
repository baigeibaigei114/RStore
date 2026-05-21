package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件上传配置属性，前缀为 "upload"。
 * <p>
 * 大文件上传会同时占用磁盘 IO、Web 线程和 Python 进程，本地开发默认保守限制。
 * 缩略图异步生成会读取 GeoTIFF 并启动 Python 进程，线程池默认保持较小规模。
 */
@Data
@ConfigurationProperties(prefix = "upload")
public class UploadProperties {

    /** 最大同时上传任务数，默认 2。 */
    private int maxConcurrent = 2;

    /** 缩略图线程池核心线程数，默认 1。 */
    private int thumbnailCorePoolSize = 1;

    /** 缩略图线程池最大线程数，默认 2。 */
    private int thumbnailMaxPoolSize = 2;

    /** 缩略图任务队列容量，默认 20。 */
    private int thumbnailQueueCapacity = 20;

    /** 缩略图重试定时任务每次处理条数，默认 20。 */
    private int thumbnailRetryBatchSize = 20;

    /** 缩略图重试定时任务的固定延迟（毫秒），默认 60000（1 分钟）。 */
    private long thumbnailRetryFixedDelayMs = 60000;
}
