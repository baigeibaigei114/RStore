package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.entity.RsTaskLog;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsTaskLogMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.RsTaskFailureService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 任务失败处理服务实现类，负责在检测到任务不可恢复的失败时，
 * 回滚任务状态（ACTIVE -> FAILED）并记录失败日志。
 *
 * <p>核心职责：
 * <ol>
 *   <li>将 ACTIVE 状态的任务标记为 FAILED（使用 SQL 条件保护终态任务）。</li>
 *   <li>如果任务所属的影像在任务失败后不再有活跃任务，
 *       将影像状态从 PROCESSING 回退为 READY，允许后续重新提交。</li>
 *   <li>插入任务失败日志，记录错误原因和详细上下文。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>SQL 条件 {@code WHERE status = 'ACTIVE'} 防止终态任务（SUCCESS/CANCELED）
 *       被失败兜底路径意外覆盖。</li>
 *   <li>{@code markReadyIfProcessing} 的条件同样是 SQL 级别的——只有当影像为 PROCESSING 状态时
 *       才会被更新，避免干扰手动设置的 READY 状态。</li>
 *   <li>@Transactional 保证 {@code markFailedIfActive} 和后续的日志插入、
 *       影像状态回退在同一事务中，不存在部分失败的问题。</li>
 * </ul>
 *
 * @author remote-sensing-platform
 */
@Service
public class RsTaskFailureServiceImpl implements RsTaskFailureService {

    private final RsTaskMapper taskMapper;
    private final RsImageMapper imageMapper;
    private final RsTaskLogMapper taskLogMapper;
    private final ObjectMapper objectMapper;

    public RsTaskFailureServiceImpl(RsTaskMapper taskMapper,
                                    RsImageMapper imageMapper,
                                    RsTaskLogMapper taskLogMapper,
                                    ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.imageMapper = imageMapper;
        this.taskLogMapper = taskLogMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 将 ACTIVE 状态的任务标记为失败，并记录失败日志。
     *
     * <p>如果任务关联的影像在该任务失败后没有其他活跃任务，
     * 将影像状态从 PROCESSING 回退为 READY，释放影像以供重新提交。
     *
     * <p>事务属性：REQUIRED（默认），确保 DB 操作和日志插入的原子性。
     *
     * @param taskId       任务 ID（为 null 时直接返回）
     * @param errorMessage 失败错误信息
     * @param detail       失败详细上下文（序列化为 JSON 存入日志）
     */
    @Override
    @Transactional
    public void markFailedIfActive(Long taskId, String errorMessage, Object detail) {
        if (taskId == null) {
            return;
        }

        RsTask task = taskMapper.selectById(taskId);
        // SQL 条件保护终态任务，避免失败兜底路径覆盖 SUCCESS/CANCELED 等最终状态。
        // WHERE status = 'ACTIVE' 保证只有活跃任务会被更新
        int updated = taskMapper.markFailedIfActive(taskId, errorMessage);
        if (updated <= 0) {
            // 没有被更新，说明任务已不在 ACTIVE 状态，不做额外处理
            return;
        }
        // 如果影像下所有任务都已终态（countActiveByImageId == 0），
        // 将影像状态从 PROCESSING 回退为 READY
        if (task != null && TaskStatus.fromDb(task.getStatus()).isActive()
                && taskMapper.countActiveByImageId(task.getImageId()) == 0) {
            imageMapper.markReadyIfProcessing(task.getImageId());
        }

        // 插入 ERROR 级别的任务日志，包含错误消息和序列化的详细上下文
        RsTaskLog taskLog = new RsTaskLog();
        taskLog.setTaskId(taskId);
        taskLog.setLogLevel("ERROR");
        taskLog.setMessage(errorMessage);
        taskLog.setDetail(toJson(detail));
        taskLogMapper.insert(taskLog);
    }

    /**
     * 将对象序列化为 JSON 字符串。
     * <p>序列化失败返回默认 JSON 而非抛出异常，避免因详情序列化失败导致整个失败处理流程中断。
     * 这是 Fail-safe 设计——失败日志详情丢失不应影响任务状态回滚。
     *
     * @param value 要序列化的对象
     * @return JSON 字符串，序列化失败时返回默认错误消息 JSON
     */
    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "{\"error\":\"任务失败详情序列化失败\"}";
        }
    }
}
