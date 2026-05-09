package com.remotesensing.platform.dto;

import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.Data;

@Data
public class RsTaskSubmitDTO {

    /**
     * params 按任务类型承载不同波段和阈值配置，例如 redBand、nirBand、greenBand。
     */
    @NotNull(message = "影像 ID 不能为空")
    private Long imageId;

    @NotNull(message = "任务类型不能为空")
    private TaskType taskType;

    private Map<String, Object> params;
}
