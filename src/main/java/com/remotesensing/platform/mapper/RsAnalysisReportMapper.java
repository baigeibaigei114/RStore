package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsAnalysisReport;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 分析报告 Mapper。
 */
@Mapper
public interface RsAnalysisReportMapper {

    /**
     * 插入 AI 分析报告记录，插入后回填自增 ID。
     */
    int insert(RsAnalysisReport report);

    /**
     * 按任务、用户和报告类型查询已有报告，用于避免重复调用模型。
     */
    RsAnalysisReport selectByTaskOwnerAndType(RsAnalysisReport report);
}
