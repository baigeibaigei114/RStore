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
            throw new IllegalArgumentException("影像状态不能为空");
        }
        try {
            return ImageStatus.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("非法影像状态：" + status, exception);
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
