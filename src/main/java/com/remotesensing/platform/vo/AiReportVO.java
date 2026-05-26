package com.remotesensing.platform.vo;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

/**
 * AI 生成的遥感任务分析报告视图对象。
 */
@Data
public class AiReportVO {

    private Long id;

    private Long taskId;

    private Long imageId;

    private String ownerId;

    private String reportType;

    private String summary;

    private Map<String, Object> reportJson;

    private OffsetDateTime createdAt;

    private OffsetDateTime updatedAt;
}
