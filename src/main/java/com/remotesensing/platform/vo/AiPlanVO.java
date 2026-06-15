package com.remotesensing.platform.vo;

import com.remotesensing.platform.dto.AiPlanContentDTO;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Data;

/**
 * AI Plan 详情视图。
 */
@Data
public class AiPlanVO {

    private Long id;

    private String status;

    private String title;

    private String userInput;

    private AiPlanContentDTO plan;

    private List<String> validationErrors;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
