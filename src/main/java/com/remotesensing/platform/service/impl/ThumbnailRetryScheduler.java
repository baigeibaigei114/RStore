package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.config.UploadProperties;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.service.ThumbnailAsyncService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    @Scheduled(fixedDelayString = "${upload.thumbnail-retry-fixed-delay-ms:60000}")
    public void retryPendingThumbnails() {
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
