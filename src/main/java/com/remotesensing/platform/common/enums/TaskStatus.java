package com.remotesensing.platform.common.enums;

import java.util.Set;

/**
 * 遥感处理任务生命周期状态枚举，对应 rs_task.status 字段。
 * <p>
 * 状态流转：
 * PENDING -> RUNNING -> SUCCESS (终态)
 * PENDING/RUNNING -> RETRYING -> RUNNING/FAILED/CANCELED
 */
public enum TaskStatus {

    /** 任务已创建，等待 Worker 抢占消费。 */
    PENDING,

    /** Worker 已抢占，任务正在执行中。 */
    RUNNING,

    /** 任务执行成功（终态）。 */
    SUCCESS,

    /** 任务执行失败（终态）。 */
    FAILED,

    /** 任务执行失败，等待重试。 */
    RETRYING,

    /** 任务被取消（终态）。 */
    CANCELED;

    /** 从数据库字符串转换为枚举，忽略大小写。 */
    public static TaskStatus fromDb(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("任务状态不能为空");
        }
        try {
            return TaskStatus.valueOf(status.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("非法任务状态：" + status, exception);
        }
    }

    /** 返回数据库持久化时使用的字符串值（枚举名）。 */
    public String dbValue() {
        return name();
    }

    /** 是否为终态（SUCCESS / FAILED / CANCELED），终态任务不再流转。 */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELED;
    }

    /** 是否为活跃状态（PENDING / RUNNING / RETRYING），活跃任务可更新。 */
    public boolean isActive() {
        return this == PENDING || this == RUNNING || this == RETRYING;
    }

    /** 是否允许 Worker 抢占（PENDING 新任务 或 RETRYING 重试任务）。 */
    public boolean canBeClaimed() {
        return this == PENDING || this == RETRYING;
    }

    /**
     * 校验当前状态是否可以转换到目标状态。
     * <p>
     * 规则：
     * - PENDING 可转为 RUNNING / RETRYING / FAILED / CANCELED
     * - RUNNING 可转为 RUNNING（进度更新）/ RETRYING / SUCCESS / FAILED / CANCELED
     * - RETRYING 可转为 RETRYING / RUNNING / FAILED / CANCELED
     * - SUCCESS / FAILED / CANCELED 为终态，仅可转为自身（幂等更新）
     */
    public boolean canTransitTo(TaskStatus target) {
        return switch (this) {
            case PENDING -> Set.of(RUNNING, RETRYING, FAILED, CANCELED).contains(target);
            case RUNNING -> Set.of(RUNNING, RETRYING, SUCCESS, FAILED, CANCELED).contains(target);
            case RETRYING -> Set.of(RETRYING, RUNNING, FAILED, CANCELED).contains(target);
            case SUCCESS -> target == SUCCESS;
            case FAILED -> target == FAILED;
            case CANCELED -> target == CANCELED;
        };
    }
}
