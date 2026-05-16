package com.remotesensing.platform.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class RsTaskListVO {

    private Long id;
    private String ownerId;
    private String taskCode;
    private Long imageId;
    private String imageName;
    private String taskType;
    private String taskName;
    private String status;
    private Integer progress;
    private String inputBucket;
    private String inputObjectKey;
    private String outputBucket;
    private String outputObjectKey;
    private String errorMessage;
    private OffsetDateTime submittedAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
}
