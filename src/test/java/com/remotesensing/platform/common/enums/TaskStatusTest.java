package com.remotesensing.platform.common.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TaskStatusTest {

    @Test
    @DisplayName("fromDb 拒绝空值和未知状态")
    void fromDbShouldRejectInvalidValue() {
        assertThatThrownBy(() -> TaskStatus.fromDb(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskStatus.fromDb(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskStatus.fromDb("DONE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("终态任务状态不能回退")
    void terminalStatusShouldNotTransitBackToRunning() {
        assertThat(TaskStatus.SUCCESS.canTransitTo(TaskStatus.SUCCESS)).isTrue();
        assertThat(TaskStatus.SUCCESS.canTransitTo(TaskStatus.RUNNING)).isFalse();
        assertThat(TaskStatus.FAILED.canTransitTo(TaskStatus.PENDING)).isFalse();
        assertThat(TaskStatus.CANCELED.canTransitTo(TaskStatus.RETRYING)).isFalse();
    }
}
