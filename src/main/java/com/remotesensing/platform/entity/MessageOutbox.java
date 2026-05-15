package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class MessageOutbox {

    private Long id;
    private String aggregateType;
    private Long aggregateId;
    private String exchangeName;
    private String routingKey;
    private String payload;
    private String status;
    private Integer retryCount;
    private Integer maxRetryCount;
    private OffsetDateTime nextRetryAt;
    private OffsetDateTime sentAt;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
