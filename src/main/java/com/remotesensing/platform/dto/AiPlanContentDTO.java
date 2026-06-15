package com.remotesensing.platform.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * AI Plan 结构化内容。
 */
@Data
public class AiPlanContentDTO {

    private String goal;

    private List<AiPlanStepDTO> steps = new ArrayList<>();
}
