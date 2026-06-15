package com.remotesensing.platform.dto;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * AI Plan 单个步骤。
 */
@Data
public class AiPlanStepDTO {

    private Integer order;

    private String type;

    private String description;

    private Map<String, Object> params = new LinkedHashMap<>();

    private Boolean requiresConfirmation;
}
