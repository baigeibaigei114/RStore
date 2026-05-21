package com.remotesensing.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 遥感任务状态更新请求 DTO。
 * 由 Worker 节点在处理过程中回调，用于向服务端上报任务进度、状态、输出路径或错误信息。
 */
@Data
public class RsTaskStatusUpdateDTO {

    /** 任务最新状态值，如 PROCESSING / COMPLETED / FAILED，对应 rs_task 表 status 列。不能为空。 */
    @NotBlank(message = "任务状态不能为空")
    private String status;

    /** 任务进度百分比，取值范围 0-100，对应 rs_task 表 progress 列。 */
    @Min(value = 0, message = "任务进度不能小于 0")
    @Max(value = 100, message = "任务进度不能大于 100")
    private Integer progress;

    /** Worker 上报的阶段性说明，用于写入任务日志，不直接作为最终错误原因。 */
    private String message;

    /** 结果文件在 MinIO 中的对象键（objectKey），对应 rs_task 表 output_object_key 列。 */
    private String outputObjectKey;

    /** 任务失败时的详细错误信息，对应 rs_task 表 error_message 列。 */
    private String errorMessage;
}
