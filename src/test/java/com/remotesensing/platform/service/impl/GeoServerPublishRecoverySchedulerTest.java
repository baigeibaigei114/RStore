package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.remotesensing.platform.config.properties.GeoServerProperties;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GeoServerPublishRecoverySchedulerTest {

    @Mock
    private RsResultFileMapper resultFileMapper;

    @Test
    void recoverStalePublishingResultsShouldUseConfiguredTimeout() {
        GeoServerProperties properties = new GeoServerProperties();
        properties.setPublishTimeoutMinutes(15);
        GeoServerPublishRecoveryScheduler scheduler = new GeoServerPublishRecoveryScheduler(resultFileMapper, properties);
        when(resultFileMapper.markStalePublishingFailed(any(), any())).thenReturn(2);

        OffsetDateTime before = OffsetDateTime.now().minusMinutes(15);
        scheduler.recoverStalePublishingResults();
        OffsetDateTime after = OffsetDateTime.now().minusMinutes(15);

        ArgumentCaptor<OffsetDateTime> deadlineCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(resultFileMapper).markStalePublishingFailed(
                deadlineCaptor.capture(),
                contains("GeoServer 发布超时")
        );
        assertThat(deadlineCaptor.getValue()).isBeforeOrEqualTo(before.plusSeconds(1));
        assertThat(deadlineCaptor.getValue()).isAfterOrEqualTo(after.minusSeconds(1));
    }
}
