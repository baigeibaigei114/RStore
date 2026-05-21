package com.remotesensing.platform.vo;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 遥感任务详情 VO。
 * 用于展示单条任务的完整信息，包含任务基本属性、输入/输出路径、进度、重试信息及时间戳。
 * 对应 rs_task 表的全部业务字段，并通过 JOIN rs_image 获取 image_name。
 */
@Data
public class RsTaskVO {

    /** 任务主键 ID，对应 rs_task.id。 */
    private Long id;

    /** 任务所属用户 ID，对应 rs_task.owner_id。 */
    private String ownerId;

    /** 任务业务编码，对应 rs_task.task_code。 */
    private String taskCode;

    /** 关联的遥感影像主键 ID，对应 rs_task.image_id。 */
    private Long imageId;

    /** 关联影像的名称，通过 JOIN rs_image 查询得到。 */
    private String imageName;

    /** 任务类型，如 NDVI / NDWI / CHANGE_DETECTION，对应 rs_task.task_type。 */
    private String taskType;

    /** 任务名称，对应 rs_task.task_name。 */
    private String taskName;

    /** 任务状态：PENDING / PROCESSING / COMPLETED / FAILED，对应 rs_task.status。 */
    private String status;

    /** 任务进度百分比（0-100），对应 rs_task.progress。 */
    private Integer progress;

    /** 已重试次数，对应 rs_task.retry_count。 */
    private Integer retryCount;

    /** 最大允许重试次数，对应 rs_task.max_retry_count。 */
    private Integer maxRetryCount;

    /** 输入文件所在 MinIO 存储桶，对应 rs_task.input_bucket。 */
    private String inputBucket;

    /** 输入文件在 MinIO 中的对象键，对应 rs_task.input_object_key。 */
    private String inputObjectKey;

    /** 结果文件所在 MinIO 存储桶，对应 rs_task.output_bucket。 */
    private String outputBucket;

    /** 结果文件在 MinIO 中的对象键，对应 rs_task.output_object_key。 */
    private String outputObjectKey;

    /** 任务参数 JSON 字符串，对应 rs_task.params。 */
    private String params;

    /** 失败时的错误信息，对应 rs_task.error_message。 */
    private String errorMessage;

    /** 任务提交时间（UTC），对应 rs_task.submitted_at。 */
    private OffsetDateTime submittedAt;

    /** 任务开始处理时间（UTC），对应 rs_task.started_at。 */
    private OffsetDateTime startedAt;

    /** 任务完成时间（UTC），对应 rs_task.finished_at。 */
    private OffsetDateTime finishedAt;

    /** 记录创建时间（UTC），对应 rs_task.created_at。 */
    private OffsetDateTime createdAt;

    /** 记录更新时间（UTC），对应 rs_task.updated_at。 */
    private OffsetDateTime updatedAt;
}
