package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.dto.AiQueryParseRequestDTO;
import com.remotesensing.platform.service.AiQueryParseService;
import com.remotesensing.platform.service.AiReportService;
import com.remotesensing.platform.vo.AiQueryIntentVO;
import com.remotesensing.platform.vo.AiReportVO;
import jakarta.validation.Valid;
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

    public AiController(AiQueryParseService aiQueryParseService, AiReportService aiReportService) {
        this.aiQueryParseService = aiQueryParseService;
        this.aiReportService = aiReportService;
    }

    /**
     * 将自然语言解析为结构化影像查询条件，仅解析不执行查询。
     */
    @PostMapping("/query/parse")
    public Result<AiQueryIntentVO> parseQuery(@Valid @RequestBody AiQueryParseRequestDTO requestDTO) {
        return Result.success(aiQueryParseService.parse(requestDTO.getText()));
    }

    /**
     * 根据任务结果统计元数据生成中文分析报告。
     */
    @PostMapping("/reports/from-task/{taskId}")
    public Result<AiReportVO> generateReportFromTask(@PathVariable Long taskId) {
        return Result.success(aiReportService.generateFromTask(taskId));
    }
}
