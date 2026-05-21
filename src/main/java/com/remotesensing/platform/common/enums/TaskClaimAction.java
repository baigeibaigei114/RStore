package com.remotesensing.platform.common.enums;

/**
 * Worker 抢占任务的结果动作枚举。
 * <p>
 * Worker 调用 claim 接口时，根据任务当前状态返回不同的动作指示：
 * - CLAIMED：抢占成功，Worker 可以开始执行。
 * - ALREADY_FINISHED：任务已被其他 Worker 完成。
 * - ALREADY_RUNNING：任务正在被其他 Worker 执行中。
 * - CLAIM_REJECTED：抢占被拒绝（如状态不允许抢占）。
 */
public enum TaskClaimAction {

    /** Worker 成功抢占任务，可以开始执行。 */
    CLAIMED,

    /** 任务已被其他 Worker 完成（终态），无需重复执行。 */
    ALREADY_FINISHED,

    /** 任务正在被其他 Worker 执行中，不应重复抢占。 */
    ALREADY_RUNNING,

    /** 抢占被拒绝（任务状态不允许抢占，如 CANCELED）。 */
    CLAIM_REJECTED;

    /** 返回数据库持久化时使用的字符串值（枚举名）。 */
    public String dbValue() {
        return name();
    }
}
