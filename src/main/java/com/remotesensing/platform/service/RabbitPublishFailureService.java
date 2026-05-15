package com.remotesensing.platform.service;

import com.remotesensing.platform.config.RabbitTaskProperties;
import com.remotesensing.platform.entity.MessageOutbox;
import com.remotesensing.platform.mapper.MessageOutboxMapper;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Service;

@Service
public class RabbitPublishFailureService {

    private static final String TASK_ID_HEADER = "taskId";

    private final MessageOutboxMapper outboxMapper;
    private final RsTaskFailureService taskFailureService;
    private final RabbitTaskProperties rabbitTaskProperties;

    public RabbitPublishFailureService(MessageOutboxMapper outboxMapper,
                                       RsTaskFailureService taskFailureService,
                                       RabbitTaskProperties rabbitTaskProperties) {
        this.outboxMapper = outboxMapper;
        this.taskFailureService = taskFailureService;
        this.rabbitTaskProperties = rabbitTaskProperties;
    }

    public void handleConfirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null) {
            return;
        }

        Long taskId = parseTaskId(correlationData.getId());
        if (taskId == null) {
            return;
        }
        if (ack) {
            outboxMapper.markSentByTaskId(taskId);
            return;
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("correlationId", correlationData.getId());
        detail.put("cause", cause);
        markOutboxFailed(taskId, "RabbitMQ 服务端未确认任务消息：" + nullToUnknown(cause), detail);
    }

    public void handleReturn(ReturnedMessage returnedMessage) {
        Long taskId = extractTaskId(returnedMessage);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("exchange", returnedMessage.getExchange());
        detail.put("routingKey", returnedMessage.getRoutingKey());
        detail.put("replyCode", returnedMessage.getReplyCode());
        detail.put("replyText", returnedMessage.getReplyText());
        detail.put("headers", returnedMessage.getMessage().getMessageProperties().getHeaders());
        markOutboxFailed(taskId, "RabbitMQ 任务消息被退回：" + returnedMessage.getReplyText(), detail);
    }

    private void markOutboxFailed(Long taskId, String errorMessage, Map<String, Object> detail) {
        if (taskId == null) {
            return;
        }
        OffsetDateTime nextRetryAt = OffsetDateTime.now()
                .plusNanos(resolveOutboxRetryDelayMs() * 1_000_000L);
        outboxMapper.markFailedByTaskId(taskId, errorMessage, nextRetryAt);

        MessageOutbox outbox = outboxMapper.selectByTaskId(taskId);
        if (outbox != null && outbox.getRetryCount() >= outbox.getMaxRetryCount()) {
            taskFailureService.markFailedIfActive(taskId, errorMessage, detail);
        }
    }

    private Long extractTaskId(ReturnedMessage returnedMessage) {
        Object value = returnedMessage.getMessage().getMessageProperties().getHeaders().get(TASK_ID_HEADER);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return parseTaskId(value == null ? null : value.toString());
    }

    private Long parseTaskId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "未知原因" : value;
    }

    private int resolveOutboxRetryDelayMs() {
        Integer value = rabbitTaskProperties.getOutboxRetryDelayMs();
        return value == null || value < 1 ? 30000 : value;
    }
}
