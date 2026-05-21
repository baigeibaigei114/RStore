package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.enums.MessageOutboxStatus;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.config.properties.RabbitTaskProperties;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage;
import com.remotesensing.platform.entity.MessageOutbox;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.mapper.MessageOutboxMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.MessageOutboxService;
import com.remotesensing.platform.service.RsTaskFailureService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * 消息 Outbox（发件箱）模式服务实现类，确保 RabbitMQ 消息的可靠投递。
 *
 * <p>核心职责：
 * <ol>
 *   <li>创建任务消息记录（状态 PENDING），持久化到数据库。</li>
 *   <li>立即投递指定 ID 的 Outbox 消息。</li>
 *   <li>定时扫描并补偿投递超时未成功的消息（{@link #publishDueMessages()}）。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>Outbox 模式保证「业务操作与消息发送」的原子性——先写 DB 再发送 MQ，
 *       发送失败通过定时任务重试，不会丢失消息。</li>
 *   <li>如果关联任务已进入终态（SUCCESS/CANCELED/FAILED），则跳过投递
 *       （{@link #markSentIfTaskTerminal(MessageOutbox)}），避免发送过期消息。</li>
 *   <li>超过最大重试次数后调用 {@link RsTaskFailureService#markFailedIfActive} 将任务标记为失败，
 *       防止消息永远无法投递导致任务卡在 PENDING 状态。</li>
 *   <li>使用乐观锁（{@code markPublishAttempt} 的 SQL 条件）防止并发投递同一 Outbox 消息。</li>
 * </ul>
 *
 * @author remote-sensing-platform
 */
@Service
public class MessageOutboxServiceImpl implements MessageOutboxService {

    private static final Logger log = LoggerFactory.getLogger(MessageOutboxServiceImpl.class);
    /** 聚合类型常量 —— 遥感任务，用于 Outbox 的 aggregateType 字段 */
    private static final String AGGREGATE_TYPE_TASK = "RS_TASK";
    /** Jackson 反序列化泛型类型引用，用于将 JSON 负载转为 Map */
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final MessageOutboxMapper outboxMapper;
    private final RsTaskMapper taskMapper;
    private final RabbitTemplate rabbitTemplate;
    private final RabbitTaskProperties rabbitTaskProperties;
    private final ObjectMapper objectMapper;
    private final RsTaskFailureService taskFailureService;

    public MessageOutboxServiceImpl(MessageOutboxMapper outboxMapper,
                                    RsTaskMapper taskMapper,
                                    RabbitTemplate rabbitTemplate,
                                    RabbitTaskProperties rabbitTaskProperties,
                                    ObjectMapper objectMapper,
                                    RsTaskFailureService taskFailureService) {
        this.outboxMapper = outboxMapper;
        this.taskMapper = taskMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitTaskProperties = rabbitTaskProperties;
        this.objectMapper = objectMapper;
        this.taskFailureService = taskFailureService;
    }

    /**
     * 创建并持久化一条待发送的任务消息记录（Outbox）。
     *
     * <p>消息初始状态为 {@link MessageOutboxStatus#PENDING}，
     * 由调用方在业务事务内调用此方法，后续通过 {@link #publishById(Long)} 或定时补偿发送。
     * 这种「先写 DB、后发 MQ」的方式避免业务操作成功但消息发送丢失的不一致问题。
     *
     * @param taskId  任务 ID，作为聚合标识
     * @param message 任务消息体，将被序列化为 JSON 后存入 payload 字段
     * @return Outbox 记录的主键 ID
     */
    @Override
    public Long createTaskMessage(Long taskId, RemoteSensingTaskMessage message) {
        MessageOutbox outbox = new MessageOutbox();
        // 初始化 Outbox 记录：聚合类型为遥感任务，初始状态 PENDING，重试计数为 0
        outbox.setAggregateType(AGGREGATE_TYPE_TASK);
        outbox.setAggregateId(taskId);
        outbox.setExchangeName(rabbitTaskProperties.getExchange());
        outbox.setRoutingKey(rabbitTaskProperties.getRoutingKey());
        outbox.setPayload(toJson(message));
        outbox.setStatus(MessageOutboxStatus.PENDING.dbValue());
        outbox.setRetryCount(0);
        outbox.setMaxRetryCount(resolveOutboxMaxRetryCount());
        outbox.setNextRetryAt(OffsetDateTime.now());
        outboxMapper.insert(outbox);
        return outbox.getId();
    }

    /**
     * 立即投递指定 ID 的 Outbox 消息。
     *
     * <p>outboxId 为空或对应记录不存在 / 已发送时直接返回。
     * 该方法通常在创建 Outbox 的同一次请求中调用，用于尝试立即发送。
     *
     * @param outboxId Outbox 记录 ID
     */
    @Override
    public void publishById(Long outboxId) {
        if (outboxId == null) {
            return;
        }
        MessageOutbox outbox = outboxMapper.selectById(outboxId);
        if (outbox == null || MessageOutboxStatus.SENT.dbValue().equals(outbox.getStatus())) {
            return;
        }
        publish(outbox);
    }

    /**
     * 定时扫描并投递到期待补偿的 Outbox 消息。
     *
     * <p>由外部调度器（如 Spring Boot 的 @Scheduled 或 XXL-Job）
     * 定期调用此方法，查询 {@code next_retry_at <= now AND status = PENDING} 的记录并尝试投递。
     * 这是 Outbox 模式的最终一致性保证——即使首次投递失败，定时补偿也会确保消息最终送达。
     *
     * <p>查询使用 batchSize 限制每次处理的消息数，避免单次扫描过多。
     */
    @Override
    public void publishDueMessages() {
        int batchSize = resolveOutboxBatchSize();
        // 查询所有 next_retry_at <= now 且 status = PENDING 的记录
        List<MessageOutbox> messages = outboxMapper.selectDueMessages(OffsetDateTime.now(), batchSize);
        if (messages.isEmpty()) {
            return;
        }
        log.info("扫描到待补偿 Outbox 消息，count={}", messages.size());
        for (MessageOutbox outbox : messages) {
            publish(outbox);
        }
    }

    /**
     * 投递单个 Outbox 消息。
     *
     * <p>投递前先检查任务是否已进入终态（{@link #markSentIfTaskTerminal(MessageOutbox)}），
     * 如果是则跳过投递（消息不需要发送了）。使用乐观锁更新状态后再实际发送，
     * 防止同一 Outbox 消息被并发投递多次。
     *
     * @param outbox Outbox 记录
     */
    private void publish(MessageOutbox outbox) {
        // 如果任务已进入终态（SUCCESS/CANCELED/FAILED），无需再发送消息
        if (markSentIfTaskTerminal(outbox)) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        // 计算下次重试时间 = 当前时间 + 重试间隔
        OffsetDateTime nextRetryAt = now.plusNanos(resolveOutboxRetryDelayMs() * 1_000_000L);
        // 乐观锁：通过 WHERE status = PENDING 条件保证只有第一个线程能成功更新并投递
        if (outboxMapper.markPublishAttempt(outbox.getId(), now, nextRetryAt) <= 0) {
            return;
        }

        try {
            // 将 JSON payload 反序列化为 Map 后再发送（RabbitMQ 消息体要求可序列化的对象）
            Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), MAP_TYPE);
            Long taskId = outbox.getAggregateId();
            // 发送到 RabbitMQ，设置消息 headers 和持久化模式
            rabbitTemplate.convertAndSend(
                    outbox.getExchangeName(),
                    outbox.getRoutingKey(),
                    payload,
                    rabbitMessage -> {
                        // header 中携带 taskId 和 outboxId，方便消费者和死信处理器回溯
                        rabbitMessage.getMessageProperties().setHeader("taskId", taskId);
                        rabbitMessage.getMessageProperties().setHeader("outboxId", outbox.getId());
                        // PERSISTENT 确保消息写入磁盘，RabbitMQ 重启后不丢失
                        rabbitMessage.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return rabbitMessage;
                    },
                    // CorrelationData 用于 Publisher Confirm 回调，可追踪消息是否被 Broker 确认
                    new CorrelationData(String.valueOf(outbox.getId()))
            );
        } catch (AmqpException | JsonProcessingException exception) {
            // 投递异常时处理重试计数和失败回调
            handlePublishException(outbox, exception, nextRetryAt);
        }
    }

    /**
     * 如果 Outbox 关联的任务已进入终态（SUCCESS / CANCELED / FAILED），
     * 将 Outbox 状态标记为 SENT（即视为已处理）并返回 true。
     *
     * <p>为什么需要此检查？当任务在消息投递前已通过其他路径完成或取消，
     * 再发送任务消息已无意义（消费者可能基于过期消息执行不必要的操作）。
     * 将 Outbox 标记为 SENT 使得补偿调度不再重试此消息。
     */
    private boolean markSentIfTaskTerminal(MessageOutbox outbox) {
        RsTask task = taskMapper.selectById(outbox.getAggregateId());
        if (task == null) {
            return false;
        }
        if (TaskStatus.fromDb(task.getStatus()).isTerminal()) {
            outboxMapper.markSentIfNotFailed(outbox.getId());
            log.info("任务已进入终态，跳过 Outbox 投递，taskId={}, status={}",
                    task.getId(), task.getStatus());
            return true;
        }
        return false;
    }

    /**
     * 处理消息投递失败的情况。
     *
     * <p>将 Outbox 状态回退到 PENDING 并更新重试时间和重试次数。
     * 如果重试次数已达上限，通过 {@link RsTaskFailureService#markFailedIfActive} 将任务标记为失败，
     * 避免任务因消息无法投递而永远处于 PENDING 状态。
     *
     * @param outbox     Outbox 记录
     * @param exception  抛出的异常
     * @param nextRetryAt 下次重试时间
     */
    private void handlePublishException(MessageOutbox outbox, Exception exception, OffsetDateTime nextRetryAt) {
        String errorMessage = truncate("Outbox 投递失败：" + exception.getMessage());
        int updated = outboxMapper.markFailedIfSending(outbox.getId(), errorMessage, nextRetryAt);
        if (updated <= 0) {
            return;
        }
        MessageOutbox latest = outboxMapper.selectById(outbox.getId());
        if (latest != null && latest.getRetryCount() >= latest.getMaxRetryCount()) {
            taskFailureService.markFailedIfActive(
                    outbox.getAggregateId(),
                    errorMessage,
                    buildFailureDetail(outbox, exception)
            );
        }
        log.warn("Outbox 投递失败，taskId={}, outboxId={}, reason={}",
                outbox.getAggregateId(), outbox.getId(), exception.getMessage());
    }

    /**
     * 构建消息投递失败的详细上下文，用于记录到任务失败日志。
     * <p>包含 outboxId、exchange、routingKey 以及异常类型和消息，
     * 方便运维人员在任务失败日志中快速定位是哪个消息投递环节出了问题。
     */
    private Map<String, Object> buildFailureDetail(MessageOutbox outbox, Exception exception) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("outboxId", outbox.getId());
        detail.put("exchange", outbox.getExchangeName());
        detail.put("routingKey", outbox.getRoutingKey());
        detail.put("exceptionType", exception.getClass().getName());
        detail.put("message", exception.getMessage());
        return detail;
    }

    /**
     * 将对象序列化为 JSON 字符串。序列化失败时抛出业务异常。
     */
    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "Outbox 消息体序列化失败");
        }
    }

    /**
     * 截断字符串到最大长度 1000 字符。
     * <p>用于限制存入数据库的错误消息长度，避免超长错误信息导致 DB 写入失败。
     */
    private String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }

    /**
     * 获取 Outbox 定时补偿的批处理大小。
     * <p>配置为空或小于 1 时默认返回 20，避免单次补偿处理过多消息。
     */
    private int resolveOutboxBatchSize() {
        Integer value = rabbitTaskProperties.getOutboxBatchSize();
        return value == null || value < 1 ? 20 : value;
    }

    /**
     * 获取 Outbox 投递失败后的重试延迟（毫秒）。
     * <p>配置为空或小于 1 时默认返回 30000（30 秒），避免失败后立即重试造成频繁无效 IO。
     */
    private int resolveOutboxRetryDelayMs() {
        Integer value = rabbitTaskProperties.getOutboxRetryDelayMs();
        return value == null || value < 1 ? 30000 : value;
    }

    /**
     * 获取 Outbox 的最大重试次数。
     * <p>配置为空或小于 1 时默认返回 5。超过上限后任务被标记为 FAILED 状态。
     */
    private int resolveOutboxMaxRetryCount() {
        Integer value = rabbitTaskProperties.getOutboxMaxRetryCount();
        return value == null || value < 1 ? 5 : value;
    }
}
