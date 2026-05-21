package com.remotesensing.platform.vo;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 任务日志 VO。
 * 用于展示遥感任务执行过程中的日志记录，辅助问题排查和进度追踪。
 */
@Data
public class RsTaskLogVO {

    /** 日志主键 ID，对应 rs_task_log.id。 */
    private Long id;

    /** 关联的任务主键 ID，对应 rs_task_log.task_id。 */
    private Long taskId;

    /** 日志级别：INFO / WARN / ERROR，对应 rs_task_log.log_level。 */
    private String logLevel;

    /** 日志摘要信息，对应 rs_task_log.message。 */
    private String message;

    /** 日志详细内容，对应 rs_task_log.detail。 */
    private String detail;

    /** 日志记录时间（UTC），对应 rs_task_log.created_at。 */
    private OffsetDateTime createdAt;
}
