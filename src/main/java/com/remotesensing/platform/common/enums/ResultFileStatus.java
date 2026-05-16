package com.remotesensing.platform.common.enums;

public enum ResultFileStatus {

    PENDING_PUBLISH,
    PUBLISHING,
    PUBLISHED,
    PUBLISH_FAILED;

    public static ResultFileStatus fromDb(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("结果文件发布状态不能为空");
        }
        try {
            return ResultFileStatus.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("非法结果文件发布状态：" + status, exception);
        }
    }

    public String dbValue() {
        return name();
    }
}
