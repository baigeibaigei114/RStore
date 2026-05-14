package com.remotesensing.platform.common.enums;

public enum ThumbnailStatus {

    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED;

    public static ThumbnailStatus fromDb(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Thumbnail status must not be blank");
        }
        try {
            return ThumbnailStatus.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid thumbnail status: " + status, exception);
        }
    }

    public String dbValue() {
        return name();
    }

    public boolean canStart() {
        return this == PENDING;
    }

    public boolean canRetry() {
        return this == PENDING;
    }
}
