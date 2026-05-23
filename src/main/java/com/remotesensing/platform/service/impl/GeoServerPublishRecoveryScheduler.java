package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.config.properties.GeoServerProperties;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * GeoServer 发布超时补偿调度器。
 *
 * <p>用于恢复 Java 进程在发布过程中崩溃导致的 PUBLISHING 悬挂状态。被恢复的记录会标记为
 * PUBLISH_FAILED，后续手动发布或补偿发布可以重新抢占发布权。
 */
@Component
@Profile("!test")
public class GeoServerPublishRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(GeoServerPublishRecoveryScheduler.class);

    private final RsResultFileMapper resultFileMapper;
    private final GeoServerProperties geoServerProperties;

    public GeoServerPublishRecoveryScheduler(RsResultFileMapper resultFileMapper,
                                             GeoServerProperties geoServerProperties) {
        this.resultFileMapper = resultFileMapper;
        this.geoServerProperties = geoServerProperties;
    }

    @Scheduled(fixedDelayString = "${geoserver.publish-recovery-fixed-delay-ms:300000}")
    public void recoverStalePublishingResults() {
        int timeoutMinutes = Math.max(1, geoServerProperties.getPublishTimeoutMinutes());
        OffsetDateTime deadline = OffsetDateTime.now().minusMinutes(timeoutMinutes);
        int recovered = resultFileMapper.markStalePublishingFailed(
                deadline,
                "GeoServer 发布超时，已由补偿任务恢复为可重试状态"
        );
        if (recovered > 0) {
            log.warn("恢复超时 GeoServer 发布记录，count={}, timeoutMinutes={}", recovered, timeoutMinutes);
        }
    }
}
