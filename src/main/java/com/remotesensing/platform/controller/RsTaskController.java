package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.dto.RsTaskStatusUpdateDTO;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.service.RsTaskService;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.RsTaskClaimVO;
import com.remotesensing.platform.vo.RsResultFileVO;
import com.remotesensing.platform.vo.RsTaskListVO;
import com.remotesensing.platform.vo.RsTaskLogVO;
import com.remotesensing.platform.vo.RsTaskSubmitVO;
import com.remotesensing.platform.vo.RsTaskVO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tasks")
public class RsTaskController {

    private final RsTaskService taskService;

    public RsTaskController(RsTaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 提交异步遥感处理任务，具体计算由 Python Worker 执行。
     */
    @PostMapping
    public Result<RsTaskSubmitVO> submit(@Valid @RequestBody RsTaskSubmitDTO submitDTO) {
        return Result.success(taskService.submit(submitDTO));
    }

    @GetMapping("/{taskId}")
    public Result<RsTaskVO> getById(@PathVariable Long taskId) {
        return Result.success(taskService.getById(taskId));
    }

    @GetMapping("/{taskId}/result")
    public Result<RsResultFileVO> getResultFile(@PathVariable Long taskId) {
        return Result.success(taskService.getResultFile(taskId));
    }

    @GetMapping("/{taskId}/result/download-url")
    public Result<FilePresignedUrlVO> getResultDownloadUrl(@PathVariable Long taskId) {
        return Result.success(taskService.getResultDownloadUrl(taskId));
    }

    @GetMapping
    public Result<PageResult<RsTaskListVO>> page(@RequestParam(required = false) Integer pageNum,
                                                 @RequestParam(required = false) Integer pageSize) {
        return Result.success(taskService.page(pageNum, pageSize));
    }

    @GetMapping("/{taskId}/logs")
    public Result<List<RsTaskLogVO>> listLogs(@PathVariable Long taskId) {
        return Result.success(taskService.listLogs(taskId));
    }

    /**
     * Worker 处理大文件前先抢占任务，避免 RabbitMQ 重复投递导致重复计算。
     */
    @PostMapping("/{taskId}/claim")
    public Result<RsTaskClaimVO> claim(@PathVariable Long taskId) {
        return Result.success(taskService.claim(taskId));
    }

    /**
     * Python Worker 通过该接口回调任务状态，不直接操作业务数据库。
     */
    @PostMapping("/{taskId}/status")
    public Result<Void> updateStatus(@PathVariable Long taskId,
                                     @Valid @RequestBody RsTaskStatusUpdateDTO updateDTO) {
        taskService.updateStatus(taskId, updateDTO);
        return Result.success();
    }
}
