package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.service.RsTaskService;
import com.remotesensing.platform.vo.RsTaskSubmitVO;
import jakarta.validation.Valid;
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
}
