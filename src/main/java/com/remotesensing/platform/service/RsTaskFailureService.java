package com.remotesensing.platform.service;

public interface RsTaskFailureService {

    /**
     * 只对仍处于活跃状态的任务写入最终失败，避免覆盖 SUCCESS/CANCELED 等终态。
     */
    void markFailedIfActive(Long taskId, String errorMessage, Object detail);
}
