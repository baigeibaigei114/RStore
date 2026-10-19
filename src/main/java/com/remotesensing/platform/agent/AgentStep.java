package com.remotesensing.platform.agent;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class AgentStep {
    private String id, executionId, operationId, clientRequestId, requestHash, kind, name, status;
    private int sequence, attempt;
    private OffsetDateTime startedAt, endedAt;
    private Long durationMs;
    private String safeInputJson, safeOutputJson, errorCode, safeErrorMessage;
    private String provider, modelRequested, modelReturned, usageJson;
}
