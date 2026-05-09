package com.remotesensing.platform.service;

public interface RsTaskFailureService {

    /**
     * 统一处理任务最终失败状态，避免死信、投递失败等入口各自维护状态。
     */
    void markFailed(Long taskId, String errorMessage, Object detail);
}
