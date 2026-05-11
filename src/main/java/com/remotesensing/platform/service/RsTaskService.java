package com.remotesensing.platform.service;

import com.remotesensing.platform.dto.RsTaskStatusUpdateDTO;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.vo.RsTaskSubmitVO;

public interface RsTaskService {

    /**
     * 提交任务时只传对象存储路径和参数，文件本体由 worker 从 MinIO 读取。
     */
    RsTaskSubmitVO submit(RsTaskSubmitDTO submitDTO);

    /**
     * 接收 Worker 状态回调，并维护任务状态、进度和执行日志。
     */
    void updateStatus(Long taskId, RsTaskStatusUpdateDTO updateDTO);
}
