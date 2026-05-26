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
 *
 * <p>提供自然语言影像检索解析和任务结果分析报告两个 AI 辅助接口。
 * LLM 仅负责"理解自然语言"和"生成自然语言描述"，不参与实际数据查询或任务执行。
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

    /**
     * 根据任务结果统计元数据生成中文分析报告。
     *
     * <p>要求任务必须处于 SUCCESS 状态且结果文件包含统计元数据（mean、std 等）。
     * 每次调用会生成新报告并持久化到 {@code rs_analysis_report} 表，历史报告不受影响。</p>
     *
     * @param taskId 已成功的处理任务 ID
     * @return AI 生成的分析报告（摘要、关键发现、风险等级、建议）
     */
    @PostMapping("/reports/from-task/{taskId}")
    public Result<AiReportVO> generateReportFromTask(@PathVariable Long taskId) {
        checkAiReportRateLimit();
        return Result.success(aiReportService.generateFromTask(taskId));
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

    /**
     * AI 报告生成限流，比查询解析更严格 —— 报告生成涉及较多 output token，
     * 调用成本更高，因此窗口更长、限制更严格。
     */
    private void checkAiReportRateLimit() {
        String userId = currentUserContext.getCurrentUserId();
        rateLimitService.check(
                "ai-report:user:" + userId,
                rateLimitProperties.getAiReportLimit(),
                Duration.ofSeconds(rateLimitProperties.getAiReportWindowSeconds())
        );
    }
}
