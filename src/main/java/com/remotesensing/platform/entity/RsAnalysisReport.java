package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * AI 分析报告实体，对应 {@code rs_analysis_report} 表。
 *
 * <p>每份报告关联一个任务和一个用户，同一任务+用户+报告类型只保留一份报告。
 * {@code reportJson} 存储 LLM 返回的完整结构化内容，
 * {@code summary} 为可读摘要，用于列表页直接展示。</p>
 */
@Data
public class RsAnalysisReport {

    /** 报告主键 ID，自增。 */
    private Long id;

    /** 关联处理任务 ID，外键引用 rs_task.id。 */
    private Long taskId;

    /** 关联影像 ID，外键引用 rs_image.id，可为空。 */
    private Long imageId;

    /** 报告所属用户 ID。 */
    private String ownerId;

    /** 报告类型：NDVI / NDWI / CHANGE_DETECTION / GENERAL。 */
    private String reportType;

    /** 报告摘要文本。 */
    private String summary;

    /** AI 生成的结构化报告 JSON，存储为 PostgreSQL jsonb 类型。 */
    private String reportJson;

    /** 报告生成时间。 */
    private OffsetDateTime createdAt;

    /** 报告更新时间。 */
    private OffsetDateTime updatedAt;
}
