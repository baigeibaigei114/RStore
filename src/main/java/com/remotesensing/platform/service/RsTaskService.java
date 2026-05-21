package com.remotesensing.platform.service;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.dto.RsTaskStatusUpdateDTO;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.vo.FilePresignedUrlVO;
import com.remotesensing.platform.vo.RsTaskClaimVO;
import com.remotesensing.platform.vo.RsResultFileVO;
import com.remotesensing.platform.vo.RsTaskListVO;
import com.remotesensing.platform.vo.RsTaskLogVO;
import com.remotesensing.platform.vo.RsTaskSubmitVO;
import com.remotesensing.platform.vo.RsTaskVO;
import java.util.List;

/**
 * 遥感任务服务接口。
 * <p>
 * 核心职责：
 * <ul>
 *   <li>提交遥感处理任务，将对象路径和处理参数发送给 Python Worker</li>
 *   <li>接收 Worker 回调，维护任务状态、进度和日志</li>
 *   <li>Worker 计算前原子抢占任务，避免重复消费</li>
 *   <li>查询任务详情、结果文件与下载预签名 URL</li>
 *   <li>分页查询任务列表与操作日志</li>
 * </ul>
 */
public interface RsTaskService {

    /**
     * 提交遥感处理任务。
     * <p>
     * 只提交对象路径和处理参数，不直接上传文件。
     * Python Worker 从 MinIO 读取 GeoTIFF 文件进行后续处理。
     * 任务提交后通过发件箱模式（Transactional Outbox）发送消息到 RabbitMQ，
     * 确保数据库事务与消息发送的最终一致性。
     *
     * @param submitDTO 任务提交参数（含对象路径、处理参数等）
     * @return 提交结果（含任务 ID、状态等）
     */
    RsTaskSubmitVO submit(RsTaskSubmitDTO submitDTO);

    /**
     * 更新任务状态、进度和日志。
     * <p>
     * 接收 Python Worker 的处理回调，维护任务的处理进度、
     * 状态流转和操作日志。支持幂等更新，重复调用不会产生副作用。
     *
     * @param taskId   任务 ID
     * @param updateDTO 状态更新参数（含新状态、进度百分比、错误信息等）
     */
    void updateStatus(Long taskId, RsTaskStatusUpdateDTO updateDTO);

    /**
     * Worker 计算前原子抢占任务。
     * <p>
     * 通过乐观锁或状态机原子更新任务状态为 PROCESSING，
     * 确保同一任务不会被多个 Worker 实例重复消费。
     * 抢占失败时返回 null，由调用方自行决定重试或放弃。
     *
     * @param taskId 任务 ID
     * @return 抢占结果（含任务上下文信息），抢占失败返回 null
     */
    RsTaskClaimVO claim(Long taskId);

    /**
     * 根据任务 ID 查询任务详情。
     *
     * @param taskId 任务 ID
     * @return 任务详情（含状态、进度、处理参数、结果信息等）
     */
    RsTaskVO getById(Long taskId);

    /**
     * 获取任务的处理结果文件信息。
     * <p>
     * 返回结果文件的元数据信息，包括文件名称、大小、MIME 类型、
     * 存储路径等。仅当任务状态为 SUCCESS 时存在结果文件。
     *
     * @param taskId 任务 ID
     * @return 结果文件信息（含文件元数据）
     */
    RsResultFileVO getResultFile(Long taskId);

    /**
     * 获取任务结果文件的预签名下载 URL。
     * <p>
     * 返回一个有时效性的 MinIO 预签名 URL，供前端直接下载结果文件。
     * URL 过期时间由 MinIO 配置决定，过期后需重新生成。
     *
     * @param taskId 任务 ID
     * @return 预签名下载 URL 信息（含 URL 和过期时间）
     */
    FilePresignedUrlVO getResultDownloadUrl(Long taskId);

    /**
     * 分页查询当前用户的任务列表。
     * <p>
     * 按创建时间倒序排列，返回任务摘要信息列表（不含日志详情）。
     * 仅返回当前登录用户的任务，无法跨用户查询。
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 分页任务列表
     */
    PageResult<RsTaskListVO> page(Integer pageNum, Integer pageSize);

    /**
     * 查询指定任务的完整操作日志列表。
     * <p>
     * 包含从任务提交到完成（或失败）过程中的所有状态变更记录，
     * 按时间顺序排列，用于任务执行过程追溯。
     *
     * @param taskId 任务 ID
     * @return 操作日志列表（按时间正序）
     */
    List<RsTaskLogVO> listLogs(Long taskId);
}
