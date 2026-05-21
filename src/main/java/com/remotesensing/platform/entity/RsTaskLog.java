package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 任务日志实体，对应 rs_task_log 表。
 * <p>
 * 记录任务执行过程中的阶段性日志，用于跟踪任务进度和排查问题。
 * Worker 在任务执行过程中上报日志，按时间顺序存储。
 */
@Data
public class RsTaskLog {

    /** 主键 ID。 */
    private Long id;

    /** 关联的任务 ID（rs_task.id）。 */
    private Long taskId;

    /** 日志级别，如 INFO / WARN / ERROR。 */
    private String logLevel;

    /** 日志消息摘要。 */
    private String message;

    /** 日志详细内容（JSON 格式的扩展信息）。 */
    private String detail;

    /** 日志记录时间。 */
    private OffsetDateTime createdAt;
}
