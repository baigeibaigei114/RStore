package com.remotesensing.platform.common.enums;

public enum TaskClaimAction {

    CLAIMED,
    ALREADY_FINISHED,
    ALREADY_RUNNING,
    CLAIM_REJECTED;

    public String dbValue() {
        return name();
    }
}
