package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.dto.RsTaskStatusUpdateDTO;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.service.RsTaskService;
import com.remotesensing.platform.vo.RsTaskClaimVO;
import com.remotesensing.platform.vo.RsTaskSubmitVO;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tasks")
public class RsTaskController {

    private final RsTaskService taskService;

    public RsTaskController(RsTaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 提交遥感处理任务，只创建任务和发送消息，实际计算由异步 worker 完成。
     */
    @PostMapping
    public Result<RsTaskSubmitVO> submit(@Valid @RequestBody RsTaskSubmitDTO submitDTO) {
        return Result.success(taskService.submit(submitDTO));
    }

    /**
     * Worker 消费消息前先抢占任务，只有抢占成功才允许执行重计算。
     */
    @PostMapping("/{taskId}/claim")
    public Result<RsTaskClaimVO> claim(@PathVariable Long taskId) {
        return Result.success(taskService.claim(taskId));
    }

    /**
     * Python Worker 通过该接口回写任务执行状态，避免直接连接业务数据库。
     */
    @PostMapping("/{taskId}/status")
    public Result<Void> updateStatus(@PathVariable Long taskId,
                                     @Valid @RequestBody RsTaskStatusUpdateDTO updateDTO) {
        taskService.updateStatus(taskId, updateDTO);
        return Result.success();
    }
}
