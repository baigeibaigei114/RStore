package com.remotesensing.platform.common.enums;

public enum MessageOutboxStatus {

    PENDING,
    SENDING,
    SENT,
    FAILED;

    public String dbValue() {
        return name();
    }
}
