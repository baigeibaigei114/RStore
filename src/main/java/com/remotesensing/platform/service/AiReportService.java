package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.AiReportVO;

/**
 * AI 任务结果报告服务。
 */
public interface AiReportService {

    /**
     * 基于指定任务的结果统计元数据生成 AI 分析报告。
     */
    AiReportVO generateFromTask(Long taskId);
}
