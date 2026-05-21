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

/**
 * 遥感处理任务控制器。
 * <p>负责异步遥感处理任务的全生命周期管理：任务提交、进度查询、结果获取、日志查看。
 * 实际计算由 Python Worker 执行，本模块仅负责任务编排与状态协调。</p>
 */
@RestController
@RequestMapping("/tasks")
public class RsTaskController {

    private final RsTaskService taskService;

    public RsTaskController(RsTaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 提交异步遥感处理任务，具体计算由 Python Worker 执行。
     * <p>提交后任务进入队列，Worker 消费后执行实际处理。
     * 任务类型由 DTO 中的 taskType 字段区分（如 NDVI 计算、影像融合等）。</p>
     *
     * @param submitDTO 任务提交参数（类型、输入影像列表、算法参数等），已启用 Bean Validation
     * @return 提交成功后的任务概要信息，包含任务 ID 供后续轮询查询
     */
    @PostMapping
    public Result<RsTaskSubmitVO> submit(@Valid @RequestBody RsTaskSubmitDTO submitDTO) {
        return Result.success(taskService.submit(submitDTO));
    }

    /**
     * 根据任务 ID 查询任务详情。
     *
     * @param taskId 任务主键 ID
     * @return 任务详细信息 VO，包含状态、进度、创建时间等
     */
    @GetMapping("/{taskId}")
    public Result<RsTaskVO> getById(@PathVariable Long taskId) {
        return Result.success(taskService.getById(taskId));
    }

    /**
     * 获取任务结果文件元数据。
     * <p>仅在任务状态为 SUCCESS 时返回有效结果，否则返回空。
     * 前端可据此判断结果是否就绪。</p>
     *
     * @param taskId 任务主键 ID
     * @return 结果文件元信息 VO
     */
    @GetMapping("/{taskId}/result")
    public Result<RsResultFileVO> getResultFile(@PathVariable Long taskId) {
        return Result.success(taskService.getResultFile(taskId));
    }

    /**
     * 获取任务结果文件的下载地址（预签名 URL）。
     * <p>返回 MinIO 预签名 URL，前端可直接用于下载结果文件。
     * 使用预签名机制避免将 MinIO 访问密钥暴露给前端。</p>
     *
     * @param taskId 任务主键 ID
     * @return 包含预签名 URL 的 VO
     */
    @GetMapping("/{taskId}/result/download-url")
    public Result<FilePresignedUrlVO> getResultDownloadUrl(@PathVariable Long taskId) {
        return Result.success(taskService.getResultDownloadUrl(taskId));
    }

    /**
     * 分页查询任务列表。
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页结果，包含任务概要信息列表
     */
    @GetMapping
    public Result<PageResult<RsTaskListVO>> page(@RequestParam(required = false) Integer pageNum,
                                                 @RequestParam(required = false) Integer pageSize) {
        return Result.success(taskService.page(pageNum, pageSize));
    }

    /**
     * 查询指定任务的运行日志列表。
     * <p>日志由 Worker 在处理过程中写入，可用于排查任务失败原因或跟踪进度。</p>
     *
     * @param taskId 任务主键 ID
     * @return 日志记录列表，按时间正序排列
     */
    @GetMapping("/{taskId}/logs")
    public Result<List<RsTaskLogVO>> listLogs(@PathVariable Long taskId) {
        return Result.success(taskService.listLogs(taskId));
    }

    /**
     * Worker 处理大文件前先抢占任务，避免 RabbitMQ 重复投递导致重复计算。
     * <p>由于 RabbitMQ 在消费确认前可能重新投递消息，Worker 收到消息后先调用此接口
     * 将任务状态标记为 PROCESSING，后续重复消息到达时通过状态判断跳过执行。
     * 此处的"抢占"本质上是乐观锁 + 状态机校验，而非分布式锁。</p>
     *
     * @param taskId 待抢占的任务 ID
     * @return 抢占结果 VO，包含是否抢占成功及当前任务状态
     */
    @PostMapping("/{taskId}/claim")
    public Result<RsTaskClaimVO> claim(@PathVariable Long taskId) {
        return Result.success(taskService.claim(taskId));
    }

    /**
     * Python Worker 通过该接口回调任务状态，不直接操作业务数据库。
     * <p>Worker 处理完一个阶段后回调此接口更新任务进度和状态。
     * 不允许 Worker 直连数据库是为了隔离数据层访问权限，所有状态变更
     * 均经过应用层校验，防止 Worker 写入非法状态。</p>
     *
     * @param taskId    任务主键 ID
     * @param updateDTO 状态更新参数（新状态、进度百分比、消息等）
     * @return 统一响应结果
     */
    @PostMapping("/{taskId}/status")
    public Result<Void> updateStatus(@PathVariable Long taskId,
                                     @Valid @RequestBody RsTaskStatusUpdateDTO updateDTO) {
        taskService.updateStatus(taskId, updateDTO);
        return Result.success();
    }
}
