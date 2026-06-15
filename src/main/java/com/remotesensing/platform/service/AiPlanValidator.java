package com.remotesensing.platform.service;

import com.remotesensing.platform.dto.AiPlanContentDTO;
import java.util.List;

/**
 * AI Plan 后端校验器。
 */
public interface AiPlanValidator {

    List<String> validate(AiPlanContentDTO plan);
}
