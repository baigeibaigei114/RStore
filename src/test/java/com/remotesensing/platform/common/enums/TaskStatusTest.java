package com.remotesensing.platform.common.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TaskStatusTest {

    @Test
    @DisplayName("fromDb rejects blank and unknown values")
    void fromDbShouldRejectInvalidValue() {
        assertThatThrownBy(() -> TaskStatus.fromDb(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskStatus.fromDb(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskStatus.fromDb("DONE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("terminal task status cannot regress")
    void terminalStatusShouldNotTransitBackToRunning() {
        assertThat(TaskStatus.SUCCESS.canTransitTo(TaskStatus.SUCCESS)).isTrue();
        assertThat(TaskStatus.SUCCESS.canTransitTo(TaskStatus.RUNNING)).isFalse();
        assertThat(TaskStatus.FAILED.canTransitTo(TaskStatus.PENDING)).isFalse();
        assertThat(TaskStatus.CANCELED.canTransitTo(TaskStatus.RETRYING)).isFalse();
    }
}
