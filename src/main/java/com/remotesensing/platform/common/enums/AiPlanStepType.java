package com.remotesensing.platform.common.enums;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI Plan 步骤白名单。
 */
public enum AiPlanStepType {

    RESOLVE_REGION,
    SEARCH_IMAGES,
    SELECT_IMAGE,
    SUBMIT_TASK,
    WAIT_TASK,
    GENERATE_REPORT,
    LIST_LAYERS,
    DOWNLOAD_RESULT;

    private static final Set<String> NAMES = Arrays.stream(values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    public static boolean isSupported(String type) {
        return type != null && NAMES.contains(type.trim().toUpperCase());
    }

    public static AiPlanStepType fromValue(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("AI Plan 步骤类型不能为空");
        }
        return AiPlanStepType.valueOf(type.trim().toUpperCase());
    }

    public boolean requiresUserConfirmation() {
        return this == SELECT_IMAGE
                || this == SUBMIT_TASK
                || this == GENERATE_REPORT
                || this == DOWNLOAD_RESULT;
    }
}
