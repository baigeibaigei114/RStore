package com.remotesensing.platform.agent;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class AgentExecution {
    private String id, ownerId, createRequestId, createRequestHash, status;
    private int version, draftVersion, modelCallCount, toolCallCount;
    private String intentJson, candidateJson, draftJson, draftHash, confirmedHash;
    private Integer confirmedVersion;
    private OffsetDateTime draftExpiresAt;
    private Long taskId;
    private String operationId, operationKind;
    private OffsetDateTime operationDeadline;
    private Integer operationVersion;
    private String explanationJson, explanationKey, resultMetadataSha256, knowledgeIndexId;
    private String errorCode, safeErrorMessage;
    private boolean retryable;
    private OffsetDateTime createdAt, updatedAt;
}
