package com.remotesensing.platform.common.enums;

public enum ImageStatus {

    UPLOADING,
    PARSING,
    READY,
    PROCESSING,
    DELETE_LOCKED,
    DELETED,
    FAILED;

    public static ImageStatus fromDb(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Image status must not be blank");
        }
        try {
            return ImageStatus.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid image status: " + status, exception);
        }
    }

    public String dbValue() {
        return name();
    }

    public boolean canSubmitTask() {
        return this == READY;
    }

    public boolean canDelete() {
        return this == READY || this == FAILED;
    }

    public boolean isDeleted() {
        return this == DELETED;
    }
}
