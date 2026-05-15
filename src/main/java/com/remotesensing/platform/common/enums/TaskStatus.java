package com.remotesensing.platform.common.enums;

import java.util.Set;

public enum TaskStatus {

    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    RETRYING,
    CANCELED;

    public static TaskStatus fromDb(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("任务状态不能为空");
        }
        try {
            return TaskStatus.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("非法任务状态：" + status, exception);
        }
    }

    public String dbValue() {
        return name();
    }

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELED;
    }

    public boolean isActive() {
        return this == PENDING || this == RUNNING || this == RETRYING;
    }

    public boolean canBeClaimed() {
        return this == PENDING || this == RETRYING;
    }

    public boolean canTransitTo(TaskStatus target) {
        return switch (this) {
            case PENDING -> Set.of(RUNNING, RETRYING, FAILED, CANCELED).contains(target);
            case RUNNING -> Set.of(RUNNING, RETRYING, SUCCESS, FAILED, CANCELED).contains(target);
            case RETRYING -> Set.of(RETRYING, RUNNING, FAILED, CANCELED).contains(target);
            case SUCCESS -> target == SUCCESS;
            case FAILED -> target == FAILED;
            case CANCELED -> target == CANCELED;
        };
    }
}
