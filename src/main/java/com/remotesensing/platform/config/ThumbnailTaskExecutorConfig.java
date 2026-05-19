package com.remotesensing.platform.config;

import com.remotesensing.platform.config.properties.UploadProperties;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
public class ThumbnailTaskExecutorConfig {

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
