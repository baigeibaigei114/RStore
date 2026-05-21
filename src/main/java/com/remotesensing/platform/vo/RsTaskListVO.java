package com.remotesensing.platform.vo;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 遥感任务列表 VO。
 * 用于展示任务列表页的摘要信息，相较于 RsTaskVO 省略了重试、参数等内部细节。
 * 通过 JOIN rs_image 获取关联影像名称。
 */
@Data
public class RsTaskListVO {

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

    /** 输入文件所在 MinIO 存储桶，对应 rs_task.input_bucket。 */
    private String inputBucket;

    /** 输入文件在 MinIO 中的对象键，对应 rs_task.input_object_key。 */
    private String inputObjectKey;

    /** 结果文件所在 MinIO 存储桶，对应 rs_task.output_bucket。 */
    private String outputBucket;

    /** 结果文件在 MinIO 中的对象键，对应 rs_task.output_object_key。 */
    private String outputObjectKey;

    /** 失败时的简要错误信息，对应 rs_task.error_message。 */
    private String errorMessage;

    /** 任务提交时间（UTC），对应 rs_task.submitted_at。 */
    private OffsetDateTime submittedAt;

    /** 任务开始处理时间（UTC），对应 rs_task.started_at。 */
    private OffsetDateTime startedAt;

    /** 任务完成时间（UTC），对应 rs_task.finished_at。 */
    private OffsetDateTime finishedAt;
}
