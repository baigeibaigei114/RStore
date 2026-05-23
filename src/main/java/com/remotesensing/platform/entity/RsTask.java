package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 遥感处理任务实体，对应 rs_task 表。
 * <p>
 * 记录任务提交、执行、完成/失败的完整生命周期。
 * 任务状态由枚举 TaskStatus 定义，包含 PENDING / RUNNING / SUCCESS / FAILED / RETRYING / CANCELED。
 * 任务输入通过 rs_task 关联 rs_image 获取 MinIO 路径，输出路径在任务创建后动态生成。
 */
@Data
public class RsTask {

    /** 主键 ID。 */
    private Long id;

    /** 任务提交者用户 ID。 */
    private String ownerId;

    /** 客户端提交时传入的幂等请求 ID，同一用户下唯一。 */
    private String clientRequestId;

    /** 任务业务编码，全局唯一。 */
    private String taskCode;

    /** 关联的影像 ID（rs_image.id）。 */
    private Long imageId;

    /** 任务类型，如 NDVI / NDWI / CHANGE_DETECTION。 */
    private String taskType;

    /** 任务名称。 */
    private String taskName;

    /** 任务状态：PENDING / RUNNING / SUCCESS / FAILED / RETRYING / CANCELED。 */
    private String status;

    /** 优先级（数值越大优先级越高）。 */
    private Integer priority;

    /** 任务进度百分比（0-100）。 */
    private Integer progress;

    /** 当前重试次数。 */
    private Integer retryCount;

    /** 最大重试次数。 */
    private Integer maxRetryCount;

    /** 结果文件输出存储桶。 */
    private String outputBucket;

    /** 结果文件对象存储键（输出路径）。 */
    private String outputObjectKey;

    /** 任务参数 JSON，按任务类型承载不同配置（如波段索引、阈值等）。 */
    private String params;

    /** 错误信息摘要。 */
    private String errorMessage;

    /** 任务提交时间。 */
    private OffsetDateTime submittedAt;

    /** 任务开始执行时间。 */
    private OffsetDateTime startedAt;

    /** 任务完成/失败时间。 */
    private OffsetDateTime finishedAt;

    /** 创建时间。 */
    private OffsetDateTime createdAt;

    /** 最后更新时间。 */
    private OffsetDateTime updatedAt;
}
