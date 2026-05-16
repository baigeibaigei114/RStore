package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 遥感处理任务实体，记录任务状态、输出路径和提交参数。
 */
@Data
public class RsTask {

    private Long id;
    private String ownerId;
    private String taskCode;
    private Long imageId;
    private String taskType;
    private String taskName;
    private String status;
    private Integer priority;
    private Integer progress;
    private Integer retryCount;
    private Integer maxRetryCount;
    private String outputBucket;
    private String outputObjectKey;
    private String params;
    private String errorMessage;
    private OffsetDateTime submittedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
