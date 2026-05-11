package com.remotesensing.platform.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RsTaskStatusUpdateDTO {

    @NotBlank(message = "任务状态不能为空")
    private String status;

    @Min(value = 0, message = "任务进度不能小于 0")
    @Max(value = 100, message = "任务进度不能大于 100")
    private Integer progress;

    /**
     * Worker 上报的阶段性说明，用于任务日志，不直接作为最终错误原因。
     */
    private String message;

    private String outputObjectKey;

    private String errorMessage;
}
