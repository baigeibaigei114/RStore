package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * AI 分析报告实体，对应 rs_analysis_report 表。
 */
@Data
public class RsAnalysisReport {

    private Long id;

    private Long taskId;

    private Long imageId;

    private String ownerId;

    private String reportType;

    private String summary;

    private String reportJson;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
