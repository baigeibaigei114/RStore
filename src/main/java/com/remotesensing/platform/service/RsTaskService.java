package com.remotesensing.platform.service;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.dto.RsTaskStatusUpdateDTO;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.vo.RsTaskClaimVO;
import com.remotesensing.platform.vo.RsResultFileVO;
import com.remotesensing.platform.vo.RsTaskListVO;
import com.remotesensing.platform.vo.RsTaskLogVO;
import com.remotesensing.platform.vo.RsTaskSubmitVO;
import com.remotesensing.platform.vo.RsTaskVO;
import java.util.List;

public interface RsTaskService {

    /**
     * 只提交对象路径和处理参数，Python Worker 从 MinIO 读取 GeoTIFF 文件。
     */
    RsTaskSubmitVO submit(RsTaskSubmitDTO submitDTO);

    /**
     * 接收 Worker 回调，维护任务状态、进度和日志。
     */
    void updateStatus(Long taskId, RsTaskStatusUpdateDTO updateDTO);

    /**
     * Worker 计算前原子抢占任务，避免重复消费导致重复处理。
     */
    RsTaskClaimVO claim(Long taskId);

    RsTaskVO getById(Long taskId);

    RsResultFileVO getResultFile(Long taskId);

    PageResult<RsTaskListVO> page(Integer pageNum, Integer pageSize);

    List<RsTaskLogVO> listLogs(Long taskId);
}
