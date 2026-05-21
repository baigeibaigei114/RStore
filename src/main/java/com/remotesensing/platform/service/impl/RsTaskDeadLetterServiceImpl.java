package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.dto.RemoteSensingTaskMessage;
import com.remotesensing.platform.service.RsTaskDeadLetterService;
import com.remotesensing.platform.service.RsTaskFailureService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Service;

/**
 * 任务死信消息处理服务实现类，处理从 RabbitMQ 死信队列（DLQ）消费到的消息。
 *
 * <p>核心职责：
 * <ul>
 *   <li>当一条任务消息进入死信队列，意味着 RabbitMQ 已放弃主队列的重试策略
 *       （通常是由于消费异常次数超过最大重试次数或消息 TTL 耗尽）。</li>
 *   <li>将对应的遥感任务标记为失败（通过 {@link RsTaskFailureService#markFailedIfActive}），
 *       附带死信消息的详细信息用于事后排查。</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>死信消息的 headers 中通常包含 {@code x-death} 数组，记录了消息被拒绝的次数、
 *       退出原因、来源队列等信息，这些信息被包含在 failure detail 中供排查。</li>
 *   <li>如果 taskId 为 null，直接跳过（无法关联到具体任务）。</li>
 * </ul>
 *
 * @author remote-sensing-platform
 */
@Service
public class RsTaskDeadLetterServiceImpl implements RsTaskDeadLetterService {

    private final RsTaskFailureService taskFailureService;

    public RsTaskDeadLetterServiceImpl(RsTaskFailureService taskFailureService) {
        this.taskFailureService = taskFailureService;
    }

    /**
     * 处理进入死信队列的任务消息——将关联任务标记为失败并记录详细死信上下文。
     *
     * <p>消息进入 DLQ 意味着 RabbitMQ 已消耗完所有重试机会（或消息 TTL 耗尽），
     * 业务层面已无法自动恢复，因此将任务直接标记为最终失败。
     *
     * @param taskMessage 原始任务消息（含任务 ID、输入输出等信息）
     * @param rawMessage  RabbitMQ 原始消息对象（含 headers、exchange、routingKey 等元数据）
     */
    @Override
    public void record(RemoteSensingTaskMessage taskMessage, Message rawMessage) {
        if (taskMessage.getTaskId() == null) {
            // 无法关联到具体任务，跳过处理
            return;
        }

        // 消息进入 DLQ 说明 RabbitMQ 已放弃主队列重试，业务任务也应进入最终失败态。
        taskFailureService.markFailedIfActive(
                taskMessage.getTaskId(),
                "任务消息进入死信队列",
                buildDetail(taskMessage, rawMessage)
        );
    }

    /**
     * 构建死信消息的详细上下文，用于写入任务失败日志。
     * <p>包含任务类型、输入输出路径、消息 headers（含 x-death 死信原因）、
     * 最后接收的 exchange 和 routing key，完整还原消息的流转路径。
     */
    private Map<String, Object> buildDetail(RemoteSensingTaskMessage taskMessage, Message rawMessage) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskType", taskMessage.getTaskType());
        detail.put("inputBucket", taskMessage.getInputBucket());
        detail.put("inputObjectKey", taskMessage.getInputObjectKey());
        detail.put("outputBucket", taskMessage.getOutputBucket());
        detail.put("outputObjectKey", taskMessage.getOutputObjectKey());
        // headers 中通常包含 x-death 数组，可用于还原失败次数、来源队列和进入死信的原因。
        detail.put("headers", rawMessage.getMessageProperties().getHeaders());
        detail.put("receivedExchange", rawMessage.getMessageProperties().getReceivedExchange());
        detail.put("receivedRoutingKey", rawMessage.getMessageProperties().getReceivedRoutingKey());
        return detail;
    }
}
