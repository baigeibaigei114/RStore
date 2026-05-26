package com.remotesensing.platform.vo;

import java.time.OffsetDateTime;
import java.util.Map;
import lombok.Data;

/**
 * AI 生成的遥感任务分析报告视图对象。
 *
 * <p>扁平字段用于列表展示，{@code reportJson} 保存 LLM 返回的完整结构
 * （keyFindings、riskLevel、suggestions 等），前端按需解析。</p>
 */
@Data
public class AiReportVO {

    /** 报告主键 ID。 */
    private Long id;

    /** 关联任务 ID。 */
    private Long taskId;

    /** 关联影像 ID，可能为空。 */
    private Long imageId;

    /** 报告所属用户 ID。 */
    private String ownerId;

    /** 报告类型：NDVI / NDWI / CHANGE_DETECTION / GENERAL。 */
    private String reportType;

    /** 报告摘要文本，用于列表页直接展示。 */
    private String summary;

    /** 完整报告 JSON，含 keyFindings、riskLevel、suggestions。 */
    private Map<String, Object> reportJson;

    /** 报告生成时间。 */
    private OffsetDateTime createdAt;

    /** 报告更新时间。 */
    private OffsetDateTime updatedAt;
}
