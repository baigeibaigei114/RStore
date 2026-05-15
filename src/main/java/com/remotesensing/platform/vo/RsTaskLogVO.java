package com.remotesensing.platform.vo;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class RsTaskLogVO {

    private Long id;
    private Long taskId;
    private String logLevel;
    private String message;
    private String detail;
    private OffsetDateTime createdAt;
}
