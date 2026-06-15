package com.remotesensing.platform.vo;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * AI Plan 列表视图。
 */
@Data
public class AiPlanListVO {

    private Long id;

    private String status;

    private String title;

    private String userInput;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
