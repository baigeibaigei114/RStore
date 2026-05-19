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

    @Override
    public void handleConfirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null) {
            return;
        }

        Long outboxId = parseLong(correlationData.getId());
        if (outboxId == null) {
            return;
        }
        if (ack) {
            outboxMapper.markSentIfSending(outboxId);
            return;
        }

        MessageOutbox outbox = outboxMapper.selectById(outboxId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("correlationId", correlationData.getId());
        detail.put("cause", cause);
        markOutboxFailed(outbox, "RabbitMQ 服务端未确认任务消息：" + nullToUnknown(cause), detail);
    }

    @Override
    public void handleReturn(ReturnedMessage returnedMessage) {
        MessageOutbox outbox = extractOutbox(returnedMessage);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("outboxId", outbox == null ? null : outbox.getId());
        detail.put("exchange", returnedMessage.getExchange());
        detail.put("routingKey", returnedMessage.getRoutingKey());
        detail.put("replyCode", returnedMessage.getReplyCode());
        detail.put("replyText", returnedMessage.getReplyText());
        detail.put("headers", returnedMessage.getMessage().getMessageProperties().getHeaders());
        markOutboxFailed(outbox, "RabbitMQ 任务消息被退回：" + returnedMessage.getReplyText(), detail);
    }

    private void markOutboxFailed(MessageOutbox outbox, String errorMessage, Map<String, Object> detail) {
        if (outbox == null) {
            return;
        }
        OffsetDateTime nextRetryAt = OffsetDateTime.now()
                .plusNanos(resolveOutboxRetryDelayMs() * 1_000_000L);
        int updated = outboxMapper.markFailedIfSending(outbox.getId(), errorMessage, nextRetryAt);
        if (updated <= 0) {
            return;
        }

        MessageOutbox latest = outboxMapper.selectById(outbox.getId());
        if (latest != null && latest.getRetryCount() >= latest.getMaxRetryCount()) {
            taskFailureService.markFailedIfActive(outbox.getAggregateId(), errorMessage, detail);
        }
    }

    private MessageOutbox extractOutbox(ReturnedMessage returnedMessage) {
        Object value = returnedMessage.getMessage().getMessageProperties().getHeaders().get(OUTBOX_ID_HEADER);
        Long outboxId = parseHeaderLong(value);
        if (outboxId != null) {
            return outboxMapper.selectById(outboxId);
        }
        Long taskId = extractTaskIdFallback(returnedMessage);
        return taskId == null ? null : outboxMapper.selectByTaskId(taskId);
    }

    private Long extractTaskIdFallback(ReturnedMessage returnedMessage) {
        Object value = returnedMessage.getMessage().getMessageProperties().getHeaders().get(TASK_ID_HEADER);
        return parseHeaderLong(value);
    }

    private Long parseHeaderLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return parseLong(value == null ? null : value.toString());
    }

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

    private String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "未知原因" : value;
    }

    private int resolveOutboxRetryDelayMs() {
        Integer value = rabbitTaskProperties.getOutboxRetryDelayMs();
        return value == null || value < 1 ? 30000 : value;
    }
}
