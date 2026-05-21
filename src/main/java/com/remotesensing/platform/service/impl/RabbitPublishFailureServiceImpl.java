package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.config.properties.RabbitTaskProperties;
import com.remotesensing.platform.entity.MessageOutbox;
import com.remotesensing.platform.mapper.MessageOutboxMapper;
import com.remotesensing.platform.service.RabbitPublishFailureService;
import com.remotesensing.platform.service.RsTaskFailureService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Service;

/**
 * RabbitMQ 消息发布失败处理服务实现类。
 *
 * <p>职责：处理 RabbitMQ 异步发布确认（Publisher Confirm）和消息退回（Returned Message）两种回调，
 * 更新出站消息表（MessageOutbox）的状态，并在超过最大重试次数时通过 {@link RsTaskFailureService}
 * 将任务标记为最终失败。</p>
 *
 * <p>核心设计点：</p>
 * <ul>
 *   <li>通过 {@code CorrelationData.getId()} 关联回调与出站记录——ID 中携带的是 outbox 主键；</li>
 *   <li>确认回调（ACK/NACK）和退回回调（Return）分开处理，因为它们的语义不同：
 *       ACK 表示 Broker 已接收并持久化，Returned 表示路由失败；</li>
 *   <li>重试次数达到上限后调用 {@link RsTaskFailureService#markFailedIfActive} 标记任务失败，
 *      触发后续人工介入或补偿流程；</li>
 *   <li>所有异常静默处理，避免回调中抛异常影响 RabbitMQ 客户端连接状态。</li>
 * </ul>
 */
@Service
public class RabbitPublishFailureServiceImpl implements RabbitPublishFailureService {

    private static final String TASK_ID_HEADER = "taskId";
    private static final String OUTBOX_ID_HEADER = "outboxId";

    private final MessageOutboxMapper outboxMapper;
    private final RsTaskFailureService taskFailureService;
    private final RabbitTaskProperties rabbitTaskProperties;

    public RabbitPublishFailureServiceImpl(MessageOutboxMapper outboxMapper,
                                       RsTaskFailureService taskFailureService,
                                       RabbitTaskProperties rabbitTaskProperties) {
        this.outboxMapper = outboxMapper;
        this.taskFailureService = taskFailureService;
        this.rabbitTaskProperties = rabbitTaskProperties;
    }

    /**
     * 处理 RabbitMQ 异步发布确认回调。
     *
     * <p>当 {@code ack = true} 时，将出站记录状态标记为已发送（SENT）。
     * 当 {@code ack = false} 时（即 NACK），记录失败原因并进入重试逻辑。</p>
     *
     * <p>注意：此回调由 RabbitMQ 客户端线程池调用，实现必须线程安全且无阻塞。
     * 不抛出任何异常，避免 RabbitMQ 客户端连接被关闭。</p>
     *
     * @param correlationData 关联数据，其 ID 为出站记录的主键
     * @param ack             true = Broker 确认接收，false = Broker 拒绝（NACK）
     * @param cause           失败原因描述（仅在 NACK 时有意义）
     */
    @Override
    public void handleConfirm(CorrelationData correlationData, boolean ack, String cause) {
        // correlationData 为 null 时没有任何上下文可关联，直接忽略。
        if (correlationData == null) {
            return;
        }

        // CorrelationData.getId() 中存储的是 outbox 主键的字符串表示。
        Long outboxId = parseLong(correlationData.getId());
        if (outboxId == null) {
            return;
        }
        // ACK 表示 Broker 已成功接收并持久化消息，将出站记录状态更新为 SENT。
        if (ack) {
            outboxMapper.markSentIfSending(outboxId);
            return;
        }

        // NACK 表示 Broker 确认失败（如内存满、内部错误），记录为 FAILED 并计算下次重试时间。
        MessageOutbox outbox = outboxMapper.selectById(outboxId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("correlationId", correlationData.getId());
        detail.put("cause", cause);
        markOutboxFailed(outbox, "RabbitMQ 服务端未确认任务消息：" + nullToUnknown(cause), detail);
    }

    /**
     * 处理 RabbitMQ 消息退回回调（mandatory 模式下路由不到队列时触发）。
     *
     * <p>从消息头中提取 outboxId 或 taskId 来关联出站记录，并记录退回详情（Exchange、RoutingKey、
     * ReplyCode、ReplyText）。</p>
     *
     * <p>退回的原因通常包括：绑定队列不存在、RoutingKey 不匹配等。与 NACK 不同，Return 发生在
     * Broker 路由阶段，而 NACK 发生在 Broker 持久化阶段。</p>
     *
     * @param returnedMessage 退回的原始消息及其元信息
     */
    @Override
    public void handleReturn(ReturnedMessage returnedMessage) {
        // 优先从消息头的 outboxId 字段关联，若不存在则降级到 taskId 关联。
        MessageOutbox outbox = extractOutbox(returnedMessage);
        // 收集退回详情，便于人工排查路由配置问题。
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("outboxId", outbox == null ? null : outbox.getId());
        detail.put("exchange", returnedMessage.getExchange());
        detail.put("routingKey", returnedMessage.getRoutingKey());
        detail.put("replyCode", returnedMessage.getReplyCode());
        detail.put("replyText", returnedMessage.getReplyText());
        detail.put("headers", returnedMessage.getMessage().getMessageProperties().getHeaders());
        markOutboxFailed(outbox, "RabbitMQ 任务消息被退回：" + returnedMessage.getReplyText(), detail);
    }

    /**
     * 将出站记录标记为失败，并检查是否需要触发最终失败流程。
     *
     * <p>流程：</p>
     * <ul>
     *   <li>CAS 更新状态从 SENDING 到 FAILED，计算重试时间；</li>
     *   <li>重新查询最新记录，当重试次数已达上限时，调用 {@link RsTaskFailureService} 标记聚合任务失败。</li>
     * </ul>
     *
     * @param outbox       出站消息记录，为 null 时不处理
     * @param errorMessage 失败描述
     * @param detail       失败详情（用于 RsTaskFailureService 的上下文）
     */
    private void markOutboxFailed(MessageOutbox outbox, String errorMessage, Map<String, Object> detail) {
        if (outbox == null) {
            return;
        }
        // 计算下次重试时间 = 当前时间 + 重试延迟（微秒转纳秒）
        OffsetDateTime nextRetryAt = OffsetDateTime.now()
                .plusNanos(resolveOutboxRetryDelayMs() * 1_000_000L);
        // 仅当当前状态为 SENDING 时更新为 FAILED，避免并发情况下覆盖其他流程的状态变更。
        int updated = outboxMapper.markFailedIfSending(outbox.getId(), errorMessage, nextRetryAt);
        if (updated <= 0) {
            return;
        }

        // 更新成功后重新查询最新记录，判断是否已超出最大重试次数。
        MessageOutbox latest = outboxMapper.selectById(outbox.getId());
        if (latest != null && latest.getRetryCount() >= latest.getMaxRetryCount()) {
            // 超出最大重试次数，通知任务失败服务进行最终标记，触发后续补偿或人工介入。
            taskFailureService.markFailedIfActive(outbox.getAggregateId(), errorMessage, detail);
        }
    }

    /**
     * 从退回消息中提取对应的出站记录。
     *
     * <p>先尝试从消息头 {@code outboxId} 字段直接获取 outbox 主键（最精确），
     * 若不存在则降级到 {@code taskId} 字段回查（兼容老版本消息格式）。</p>
     *
     * @param returnedMessage 退回的原始消息
     * @return 关联的出站记录，未找到时返回 null
     */
    private MessageOutbox extractOutbox(ReturnedMessage returnedMessage) {
        Object value = returnedMessage.getMessage().getMessageProperties().getHeaders().get(OUTBOX_ID_HEADER);
        Long outboxId = parseHeaderLong(value);
        if (outboxId != null) {
            return outboxMapper.selectById(outboxId);
        }
        // 降级策略：通过 taskId 回查 outbox（兼容消息头中只有 taskId 的场景）。
        Long taskId = extractTaskIdFallback(returnedMessage);
        return taskId == null ? null : outboxMapper.selectByTaskId(taskId);
    }

    /**
     * 从退回消息头中提取 taskId（降级查找策略）。
     *
     * @param returnedMessage 退回的原始消息
     * @return taskId，解析失败时返回 null
     */
    private Long extractTaskIdFallback(ReturnedMessage returnedMessage) {
        Object value = returnedMessage.getMessage().getMessageProperties().getHeaders().get(TASK_ID_HEADER);
        return parseHeaderLong(value);
    }

    /**
     * 将消息头中的值安全地解析为 Long 类型。
     *
     * <p>调用的是 AMQP 消息头中的值，类型可能为 {@link Number}、{@link String} 甚至 null，
     * 需要统一处理。</p>
     *
     * @param value 消息头中的值
     * @return 解析后的 Long 值，无效时返回 null
     */
    private Long parseHeaderLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return parseLong(value == null ? null : value.toString());
    }

    /**
     * 将字符串安全解析为 Long。
     *
     * @param value 数字字符串
     * @return 解析后的 Long 值，输入为空或格式错误时返回 null
     */
    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * 将 null 或空白字符串转换为"未知原因"。
     *
     * @param value 原始字符串
     * @return 原始字符串（非空时），或"未知原因"（空时）
     */
    private String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "未知原因" : value;
    }

    /**
     * 解析重试延迟毫秒数，确保返回值合法（至少 1 毫秒）。
     *
     * @return 重试延迟毫秒数，配置无效时默认 30000 毫秒
     */
    private int resolveOutboxRetryDelayMs() {
        Integer value = rabbitTaskProperties.getOutboxRetryDelayMs();
        return value == null || value < 1 ? 30000 : value;
    }
}