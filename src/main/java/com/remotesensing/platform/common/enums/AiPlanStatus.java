package com.remotesensing.platform.common.enums;

/**
 * AI Plan 生命周期状态，对应 ai_plan.status 字段。
 */
public enum AiPlanStatus {

    DRAFT,
    VALID,
    INVALID,
    CONFIRMED,
    CANCELED;

    public static AiPlanStatus fromDb(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("AI Plan 状态不能为空");
        }
        try {
            return AiPlanStatus.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("非法 AI Plan 状态：" + status, exception);
        }
    }

    public String dbValue() {
        return name();
    }
}
