package com.remotesensing.platform.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Worker 节点领取任务响应 VO。
 * 用于向 Worker 返回任务领取结果，包含是否成功领取、需执行的动作及任务当前状态。
 */
@Data
@AllArgsConstructor
public class RsTaskClaimVO {

    /** 是否成功领取任务（true=已领取，false=无待处理任务或已被其他 Worker 领取）。 */
    private Boolean claimed;

    /** 领取成功后需要 Worker 执行的动作描述，如 "PROCESS"。 */
    private String action;

    /** 领取时的任务状态，如 "PENDING"，对应 rs_task.status。 */
    private String taskStatus;

    /** 返回给 Worker 的附加消息或说明。 */
    private String message;

    /** 结果文件在 MinIO 中的预期对象键，Worker 处理完成后写入该路径。 */
    private String outputObjectKey;
}
