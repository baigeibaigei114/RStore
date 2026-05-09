package com.remotesensing.platform.dto;

import java.io.Serializable;
import java.util.Map;
import lombok.Data;

/**
 * 遥感处理任务消息体。
 * 消息只传递对象存储位置和任务参数，文件本体始终保存在 MinIO。
 */
@Data
public class RemoteSensingTaskMessage implements Serializable {

    private Long taskId;
    private TaskType taskType;
    private String inputBucket;
    private String inputObjectKey;
    private String outputBucket;
    private String outputObjectKey;
    private Map<String, Object> params;

    public enum TaskType {
        NDVI,
        NDWI,
        CHANGE_DETECTION
    }
}
