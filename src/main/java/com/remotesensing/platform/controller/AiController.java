package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.properties.RateLimitProperties;
import com.remotesensing.platform.dto.AiQueryParseRequestDTO;
import com.remotesensing.platform.service.AiQueryParseService;
import com.remotesensing.platform.service.AiReportService;
import com.remotesensing.platform.service.RateLimitService;
import com.remotesensing.platform.vo.AiQueryIntentVO;
import com.remotesensing.platform.vo.AiReportVO;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 能力控制器。
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiQueryParseService aiQueryParseService;
    private final AiReportService aiReportService;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;
    private final CurrentUserContext currentUserContext;

    public AiController(AiQueryParseService aiQueryParseService,
                        AiReportService aiReportService,
                        RateLimitService rateLimitService,
                        RateLimitProperties rateLimitProperties,
                        CurrentUserContext currentUserContext) {
        this.aiQueryParseService = aiQueryParseService;
        this.aiReportService = aiReportService;
        this.rateLimitService = rateLimitService;
        this.rateLimitProperties = rateLimitProperties;
        this.currentUserContext = currentUserContext;
    }

    /**
     * 将自然语言解析为结构化影像查询条件，仅解析不执行查询。
     */
    @PostMapping("/query/parse")
    public Result<AiQueryIntentVO> parseQuery(@Valid @RequestBody AiQueryParseRequestDTO requestDTO) {
        checkAiQueryRateLimit();
        return Result.success(aiQueryParseService.parse(requestDTO.getText()));
    }

    /**
     * 根据任务结果统计元数据生成中文分析报告。
     */
    @PostMapping("/reports/from-task/{taskId}")
    public Result<AiReportVO> generateReportFromTask(@PathVariable Long taskId) {
        checkAiReportRateLimit();
        return Result.success(aiReportService.generateFromTask(taskId));
    }

    private void checkAiQueryRateLimit() {
        String userId = currentUserContext.getCurrentUserId();
        rateLimitService.check(
                "ai-query:user:" + userId,
                rateLimitProperties.getAiQueryLimit(),
                Duration.ofSeconds(rateLimitProperties.getAiQueryWindowSeconds())
        );
    }

    private void checkAiReportRateLimit() {
        String userId = currentUserContext.getCurrentUserId();
        rateLimitService.check(
                "ai-report:user:" + userId,
                rateLimitProperties.getAiReportLimit(),
                Duration.ofSeconds(rateLimitProperties.getAiReportWindowSeconds())
        );
    }
}
