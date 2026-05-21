package com.remotesensing.platform.config;

import com.remotesensing.platform.config.properties.UploadProperties;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 缩略图任务线程池配置类。
 * <p>
 * 职责：
 * - 创建缩略图异步生成的线程池，避免 GeoTIFF 读取和 Python 子进程调用阻塞 Web 线程。
 * - 启用 @EnableScheduling，为缩略图重试定时任务提供支持。
 * <p>
 * 线程池参数（默认值）：
 * - corePoolSize = 1，maxPoolSize = 2，queueCapacity = 20
 * - 拒绝策略为 AbortPolicy，异常由异步调用方捕获处理。
 */
@Configuration
@EnableScheduling
public class ThumbnailTaskExecutorConfig {

    /**
     * 缩略图线程池 Bean。
     * 从 UploadProperties 读取核心线程数、最大线程数和队列容量，
     * 使用 Math.max 确保配置值至少为 1。
     */
    @Bean(name = "thumbnailTaskExecutor")
    public ThreadPoolTaskExecutor thumbnailTaskExecutor(UploadProperties uploadProperties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, uploadProperties.getThumbnailCorePoolSize()));
        executor.setMaxPoolSize(Math.max(executor.getCorePoolSize(), uploadProperties.getThumbnailMaxPoolSize()));
        executor.setQueueCapacity(Math.max(1, uploadProperties.getThumbnailQueueCapacity()));
        executor.setThreadNamePrefix("rs-thumbnail-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
