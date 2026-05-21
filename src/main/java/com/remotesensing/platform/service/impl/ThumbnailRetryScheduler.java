package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.config.properties.UploadProperties;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.service.ThumbnailAsyncService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 缩略图生成失败/未处理任务的定时补偿调度器。
 *
 * <p>职责：周期性扫描数据库中状态为 {@code PENDING} 的影像记录，将它们的缩略图生成任务
 * 重新提交到异步线程池。作为 {@link ThumbnailAsyncServiceImpl#generateAsync(Long)} 的补偿机制，
 * 处理因线程池拒绝或意外错误而未被处理的 PENDING 任务。</p>
 *
 * <p>核心设计点：</p>
 * <ul>
 *   <li>按批次扫描（batchSize 可配置），避免一次性加载过多记录导致内存压力；</li>
 *   <li>本身不执行缩略图生成逻辑，仅触发异步提交，生成在独立线程中完成；</li>
 *   <li>{@code fixedDelay} 策略确保任务不重叠；</li>
 *   <li>标注 {@code @Profile("!test")} 在测试环境下自动禁用。</li>
 * </ul>
 */
@Component
@Profile("!test")
public class ThumbnailRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ThumbnailRetryScheduler.class);

    private final RsImageMapper imageMapper;
    private final ThumbnailAsyncService thumbnailAsyncService;
    private final UploadProperties uploadProperties;

    public ThumbnailRetryScheduler(RsImageMapper imageMapper,
                                   ThumbnailAsyncService thumbnailAsyncService,
                                   UploadProperties uploadProperties) {
        this.imageMapper = imageMapper;
        this.thumbnailAsyncService = thumbnailAsyncService;
        this.uploadProperties = uploadProperties;
    }

    /**
     * 扫描并重试所有 PENDING 状态的缩略图生成任务。
     *
     * <p>调度频率：上一次执行完成后，等待配置的延迟毫秒数（默认 60 秒）再执行下一次。</p>
     *
     * <p>触发条件：应用启动后立即执行第一次，之后按固定延迟持续触发。
     * 被 {@code @Profile("!test")} 限制——test profile 下不加载此 Bean。</p>
     *
     * <p>关键约束：</p>
     * <ul>
     *   <li>使用配置的 batchSize 限制每次扫描的记录数，防止 OOM；</li>
     *   <li>即使某个 imageId 提交失败（线程池满），也不会影响批次中的其他任务；</li>
     *   <li>PENDING 状态的任务会在下一次调度中继续被扫描到并重试。</li>
     * </ul>
     */
    @Scheduled(fixedDelayString = "${upload.thumbnail-retry-fixed-delay-ms:60000}")
    public void retryPendingThumbnails() {
        // 确保 batchSize 至少为 1，防止配置错误导致 SQL 非法参数。
        int batchSize = Math.max(1, uploadProperties.getThumbnailRetryBatchSize());
        List<Long> imageIds = imageMapper.selectPendingThumbnailImageIds(batchSize);
        if (imageIds.isEmpty()) {
            return;
        }

        log.info("扫描到待补偿缩略图任务，count={}", imageIds.size());
        for (Long imageId : imageIds) {
            thumbnailAsyncService.generateAsync(imageId);
        }
    }
}