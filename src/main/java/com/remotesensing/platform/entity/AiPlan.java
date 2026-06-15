package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * AI Plan 实体，对应 ai_plan 表。
 */
@Data
public class AiPlan {

    private Long id;

    private String userId;

    private String userInput;

    private String title;

    private String status;

    private String planJson;

    private String validationErrors;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
