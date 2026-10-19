package com.remotesensing.platform.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import com.remotesensing.platform.exception.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TaskSubmissionIntentTest {
    private RsTaskSubmitDTO request() {
        var dto = new RsTaskSubmitDTO();
        dto.setImageId(10L);
        dto.setTaskType(TaskType.NDVI);
        return dto;
    }

    private String stored(RsTaskSubmitDTO dto) {
        return new ObjectMapper().createObjectNode()
                .set(TaskSubmissionIntent.FIELD, TaskSubmissionIntent.from(dto)).toString();
    }

    @Test void ignoresServerFieldsAndObjectOrderButNotClientParams() {
        var first = request();
        first.setParams(Map.of("redBand", 3, "nirBand", 4));
        var retry = request();
        retry.setParams(Map.of("nirBand", 4, "redBand", 3, "regionSnapshot", Map.of("name", "forged")));
        assertThatCode(() -> TaskSubmissionIntent.requireMatch(stored(first), TaskSubmissionIntent.from(retry)))
                .doesNotThrowAnyException();
        retry.setMonitoringRegionId(1L);
        assertThatThrownBy(() -> TaskSubmissionIntent.requireMatch(stored(first), TaskSubmissionIntent.from(retry)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不同");
    }

    @Test void detectsEveryBusinessFieldAndIgnoresRequestKey() {
        var original = request();
        String saved = stored(original);
        for (int change = 0; change < 4; change++) {
            var retry = request();
            switch (change) {
                case 0 -> retry.setImageId(11L);
                case 1 -> retry.setTaskType(TaskType.NDWI);
                case 2 -> retry.setMonitoringRegionId(1L);
                case 3 -> retry.setParams(Map.of("redBand", 2));
            }
            assertThatThrownBy(() -> TaskSubmissionIntent.requireMatch(saved, TaskSubmissionIntent.from(retry)))
                    .isInstanceOf(BusinessException.class);
        }
        original.setClientRequestId("another-key");
        assertThatCode(() -> TaskSubmissionIntent.requireMatch(saved, TaskSubmissionIntent.from(original)))
                .doesNotThrowAnyException();
    }

    @Test void nullAndEmptyParamsMatchButHistoricalKeysFailClosed() {
        var dto = request();
        String saved = stored(dto);
        dto.setParams(Map.of());
        assertThatCode(() -> TaskSubmissionIntent.requireMatch(saved, TaskSubmissionIntent.from(dto)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TaskSubmissionIntent.requireMatch("{}", TaskSubmissionIntent.from(dto)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("历史");
    }
}
