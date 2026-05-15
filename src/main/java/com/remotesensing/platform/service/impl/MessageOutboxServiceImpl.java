package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.enums.MessageOutboxStatus;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.config.RabbitTaskProperties;
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

@Service
public class MessageOutboxServiceImpl implements MessageOutboxService {

    private static final Logger log = LoggerFactory.getLogger(MessageOutboxServiceImpl.class);
    private static final String AGGREGATE_TYPE_TASK = "RS_TASK";
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

    @Override
    public Long createTaskMessage(Long taskId, RemoteSensingTaskMessage message) {
        MessageOutbox outbox = new MessageOutbox();
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

    @Override
    public void publishDueMessages() {
        int batchSize = resolveOutboxBatchSize();
        List<MessageOutbox> messages = outboxMapper.selectDueMessages(OffsetDateTime.now(), batchSize);
        if (messages.isEmpty()) {
            return;
        }
        log.info("扫描到待补偿 Outbox 消息，count={}", messages.size());
        for (MessageOutbox outbox : messages) {
            publish(outbox);
        }
    }

    private void publish(MessageOutbox outbox) {
        if (markSentIfTaskTerminal(outbox)) {
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime nextRetryAt = now.plusNanos(resolveOutboxRetryDelayMs() * 1_000_000L);
        if (outboxMapper.markPublishAttempt(outbox.getId(), now, nextRetryAt) <= 0) {
            return;
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), MAP_TYPE);
            Long taskId = outbox.getAggregateId();
            rabbitTemplate.convertAndSend(
                    outbox.getExchangeName(),
                    outbox.getRoutingKey(),
                    payload,
                    rabbitMessage -> {
                        rabbitMessage.getMessageProperties().setHeader("taskId", taskId);
                        rabbitMessage.getMessageProperties().setHeader("outboxId", outbox.getId());
                        rabbitMessage.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return rabbitMessage;
                    },
                    new CorrelationData(String.valueOf(taskId))
            );
        } catch (AmqpException | JsonProcessingException exception) {
            handlePublishException(outbox, exception, nextRetryAt);
        }
    }

    private boolean markSentIfTaskTerminal(MessageOutbox outbox) {
        RsTask task = taskMapper.selectById(outbox.getAggregateId());
        if (task == null) {
            return false;
        }
        if (TaskStatus.fromDb(task.getStatus()).isTerminal()) {
            outboxMapper.markSentByTaskId(outbox.getAggregateId());
            log.info("任务已进入终态，跳过 Outbox 投递，taskId={}, status={}",
                    task.getId(), task.getStatus());
            return true;
        }
        return false;
    }

    private void handlePublishException(MessageOutbox outbox, Exception exception, OffsetDateTime nextRetryAt) {
        String errorMessage = truncate("Outbox 投递失败：" + exception.getMessage());
        outboxMapper.markFailedByTaskId(outbox.getAggregateId(), errorMessage, nextRetryAt);
        MessageOutbox latest = outboxMapper.selectByTaskId(outbox.getAggregateId());
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

    private Map<String, Object> buildFailureDetail(MessageOutbox outbox, Exception exception) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("outboxId", outbox.getId());
        detail.put("exchange", outbox.getExchangeName());
        detail.put("routingKey", outbox.getRoutingKey());
        detail.put("exceptionType", exception.getClass().getName());
        detail.put("message", exception.getMessage());
        return detail;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "Outbox 消息体序列化失败");
        }
    }

    private String truncate(String message) {
        if (message == null || message.length() <= 1000) {
            return message;
        }
        return message.substring(0, 1000);
    }

    private int resolveOutboxBatchSize() {
        Integer value = rabbitTaskProperties.getOutboxBatchSize();
        return value == null || value < 1 ? 20 : value;
    }

    private int resolveOutboxRetryDelayMs() {
        Integer value = rabbitTaskProperties.getOutboxRetryDelayMs();
        return value == null || value < 1 ? 30000 : value;
    }

    private int resolveOutboxMaxRetryCount() {
        Integer value = rabbitTaskProperties.getOutboxMaxRetryCount();
        return value == null || value < 1 ? 5 : value;
    }
}
