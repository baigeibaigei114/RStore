package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.remotesensing.platform.exception.BusinessException;

/** Only platform-owned messages and filtered accounting may cross this boundary. */
public class AgentFailure extends BusinessException {
    public final String errorCode;
    public final boolean retryable;
    public JsonNode trace;

    public AgentFailure(String code, String message, int businessCode, boolean retryable) {
        super(businessCode, message);
        this.errorCode = code;
        this.retryable = retryable;
    }

    public static AgentFailure conflict(String code) {
        return new AgentFailure(code, "执行状态或请求版本已变化，请刷新后重试", 409, false);
    }
}
