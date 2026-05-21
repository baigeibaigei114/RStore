package com.remotesensing.platform.dto;

import java.io.Serializable;
import java.util.Map;
import lombok.Data;

/**
 * 遥感处理任务消息体，用于 RabbitMQ 消息队列传输。
 * 消息中只传递对象存储位置和任务参数，文件本体始终保存在 MinIO，避免消息体过大。
 * 实现了 Serializable 以支持 RabbitMQ 的消息序列化。
 */
@Data
public class RemoteSensingTaskMessage implements Serializable {

    /** 任务主键 ID，对应 rs_task 表 id 列，用于回写状态和结果。 */
    private Long taskId;

    /** 任务类型枚举，决定 Worker 端执行的具体算法流程。 */
    private TaskType taskType;

    /** 输入文件所在的 MinIO 存储桶名称。 */
    private String inputBucket;

    /** 输入文件在 MinIO 中的对象键（objectKey）。 */
    private String inputObjectKey;

    /** 结果文件写入的 MinIO 存储桶名称。 */
    private String outputBucket;

    /** 结果文件在 MinIO 中的对象键（objectKey），任务完成后写入该位置。 */
    private String outputObjectKey;

    /** 任务参数字典，承载波段索引、阈值等算法配置。 */
    private Map<String, Object> params;

    /**
     * 遥感处理任务类型枚举。
     * NDVI：归一化植被指数计算；
     * NDWI：归一化水体指数计算；
     * CHANGE_DETECTION：遥感影像变化检测。
     */
    public enum TaskType {
        NDVI,
        NDWI,
        CHANGE_DETECTION
    }
}
