package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.config.properties.RateLimitProperties;
import com.remotesensing.platform.dto.AiPlanCreateDTO;
import com.remotesensing.platform.dto.AiPlanSearchDTO;
import com.remotesensing.platform.service.AiPlanService;
import com.remotesensing.platform.service.RateLimitService;
import com.remotesensing.platform.vo.AiPlanListVO;
import com.remotesensing.platform.vo.AiPlanVO;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI Plan 控制器，只生成和管理计划，不执行计划步骤。
 */
@RestController
@RequestMapping("/ai/plans")
public class AiPlanController {

    private final AiPlanService aiPlanService;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;
    private final CurrentUserContext currentUserContext;

    public AiPlanController(AiPlanService aiPlanService,
                            RateLimitService rateLimitService,
                            RateLimitProperties rateLimitProperties,
                            CurrentUserContext currentUserContext) {
        this.aiPlanService = aiPlanService;
        this.rateLimitService = rateLimitService;
        this.rateLimitProperties = rateLimitProperties;
        this.currentUserContext = currentUserContext;
    }

    @PostMapping
    public Result<AiPlanVO> create(@Valid @RequestBody AiPlanCreateDTO requestDTO) {
        checkAiPlanRateLimit();
        return Result.success(aiPlanService.create(requestDTO));
    }

    @GetMapping("/{id}")
    public Result<AiPlanVO> getById(@PathVariable Long id) {
        return Result.success(aiPlanService.getById(id));
    }

    @GetMapping
    public Result<PageResult<AiPlanListVO>> page(@RequestParam(required = false) Integer pageNum,
                                                 @RequestParam(required = false) Integer pageSize,
                                                 @RequestParam(required = false) String status) {
        AiPlanSearchDTO query = new AiPlanSearchDTO();
        query.setStatus(status);
        return Result.success(aiPlanService.page(query, pageNum, pageSize));
    }

    @PatchMapping("/{id}/confirm")
    public Result<AiPlanVO> confirm(@PathVariable Long id) {
        return Result.success(aiPlanService.confirm(id));
    }

    @PatchMapping("/{id}/cancel")
    public Result<AiPlanVO> cancel(@PathVariable Long id) {
        return Result.success(aiPlanService.cancel(id));
    }

    private void checkAiPlanRateLimit() {
        String userId = currentUserContext.getCurrentUserId();
        rateLimitService.check(
                "ai-plan:user:" + userId,
                rateLimitProperties.getAiPlanLimit(),
                Duration.ofSeconds(rateLimitProperties.getAiPlanWindowSeconds())
        );
    }
}
