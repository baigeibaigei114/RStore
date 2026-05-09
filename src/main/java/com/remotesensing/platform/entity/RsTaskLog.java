package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

@Data
public class RsTaskLog {

    private Long id;
    private Long taskId;
    private String logLevel;
    private String message;
    private String detail;
    private OffsetDateTime createdAt;
}
