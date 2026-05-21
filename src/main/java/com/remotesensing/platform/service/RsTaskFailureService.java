package com.remotesensing.platform.service;

/**
 * 任务失败处理服务接口。
 * <p>
 * 在消息投递失败或进入死信队列后，将任务标记为最终失败状态。
 * 只在任务仍处于活跃状态时执行标记，避免覆盖已成功的任务状态。
 */
public interface RsTaskFailureService {

    /**
     * 只对仍处于活跃状态的任务写入最终失败，避免覆盖 SUCCESS/CANCELED 等终态。
     *
     * @param taskId       任务 ID
     * @param errorMessage 错误信息摘要
     * @param detail       错误详细内容（JSON 格式）
     */
    void markFailedIfActive(Long taskId, String errorMessage, Object detail);
}
