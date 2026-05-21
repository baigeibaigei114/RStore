package com.remotesensing.platform.dto;

import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import lombok.Data;

/**
 * 遥感任务提交请求 DTO。
 * 用于接收前端提交新遥感处理任务时传入的请求体，包含目标影像 ID、任务类型及算法参数。
 */
@Data
public class RsTaskSubmitDTO {

    /** 待处理的遥感影像主键 ID，对应 rs_image 表的 id 列。不能为空。 */
    @NotNull(message = "影像 ID 不能为空")
    private Long imageId;

    /** 任务类型枚举（NDVI / NDWI / CHANGE_DETECTION），对应 RemoteSensingTaskMessage.TaskType。不能为空。 */
    @NotNull(message = "任务类型不能为空")
    private TaskType taskType;

    /**
     * 任务参数字典，按任务类型承载不同配置。
     * NDVI/NDWI 任务可包含 redBand、nirBand、greenBand 等波段索引；
     * CHANGE_DETECTION 可包含 beforeImageId、afterImageId 等。
     */
    private Map<String, Object> params;
}
