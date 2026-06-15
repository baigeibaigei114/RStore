package com.remotesensing.platform.service;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.dto.AiPlanCreateDTO;
import com.remotesensing.platform.dto.AiPlanSearchDTO;
import com.remotesensing.platform.vo.AiPlanListVO;
import com.remotesensing.platform.vo.AiPlanVO;

/**
 * AI Plan 业务服务。
 */
public interface AiPlanService {

    AiPlanVO create(AiPlanCreateDTO requestDTO);

    AiPlanVO getById(Long id);

    PageResult<AiPlanListVO> page(AiPlanSearchDTO query, Integer pageNum, Integer pageSize);

    AiPlanVO confirm(Long id);

    AiPlanVO cancel(Long id);
}
