package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.common.ResultCode;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 能力控制器。
 *
 * <p>提供自然语言影像检索解析和历史任务报告读取。
 * LLM 仅负责"理解自然语言"，不参与实际数据查询或任务执行。
 * 限流按用户维度独立控制，防止高频调用耗尽模型 API 配额。</p>
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
     * 将自然语言解析为结构化影像查询条件。
     *
     * <p>仅解析不执行查询 —— 返回的 {@link AiQueryIntentVO} 由前端/调用方
     * 自行填入现有搜索接口。这样设计是为了保持 AI 调用与数据查询的解耦，
     * 避免 LLM 输出的参数未经用户确认就直接执行。</p>
     *
     * @param requestDTO 包含用户自然语言查询文本
     * @return 结构化的检索意图（区域、时间、传感器、云量、任务类型等）
     */
    @PostMapping("/query/parse")
    public Result<AiQueryIntentVO> parseQuery(@Valid @RequestBody AiQueryParseRequestDTO requestDTO) {
        checkAiQueryRateLimit();
        return Result.success(aiQueryParseService.parse(requestDTO.getText()));
    }

    /** Retired write route retained as an explicit compatibility response. */
    @PostMapping("/reports/from-task/{taskId}")
    public Result<AiReportVO> generateReportFromTask(@PathVariable Long taskId) {
        return Result.fail(ResultCode.FORBIDDEN.getCode(), "旧报告已改为历史查看，请查看任务统计或已保存解读");
    }

    @GetMapping("/reports/from-task/{taskId}")
    public Result<AiReportVO> getSavedReport(@PathVariable Long taskId) {
        return Result.success(aiReportService.getSavedFromTask(taskId));
    }

    /**
     * AI 查询解析限流，按用户维度控制，防止高频调用耗尽模型 API 配额。
     */
    private void checkAiQueryRateLimit() {
        String userId = currentUserContext.getCurrentUserId();
        rateLimitService.check(
                "ai-query:user:" + userId,
                rateLimitProperties.getAiQueryLimit(),
                Duration.ofSeconds(rateLimitProperties.getAiQueryWindowSeconds())
        );
    }

}
