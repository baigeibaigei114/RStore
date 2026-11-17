package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsAnalysisReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 分析报告 Mapper。
 */
@Mapper
public interface RsAnalysisReportMapper {

    /**
     * 按任务、用户和报告类型读取已保存的历史报告。
     */
    RsAnalysisReport selectByTaskOwnerAndType(RsAnalysisReport report);
}
