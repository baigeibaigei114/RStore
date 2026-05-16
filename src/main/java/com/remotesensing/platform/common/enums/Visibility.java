package com.remotesensing.platform.common.enums;

public enum Visibility {
    PRIVATE,
    PUBLIC;

    public static Visibility fromDb(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("visibility 不能为空");
        }
        return Visibility.valueOf(value.trim().toUpperCase());
    }

    public String dbValue() {
        return name();
    }
}
