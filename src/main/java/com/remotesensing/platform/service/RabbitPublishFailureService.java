package com.remotesensing.platform.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Service;

@Service
public class RabbitPublishFailureService {

    private static final String TASK_ID_HEADER = "taskId";

    private final RsTaskFailureService taskFailureService;

    public RabbitPublishFailureService(RsTaskFailureService taskFailureService) {
        this.taskFailureService = taskFailureService;
    }

    public void handleConfirm(CorrelationData correlationData, boolean ack, String cause) {
        if (ack || correlationData == null) {
            return;
        }

        Long taskId = parseTaskId(correlationData.getId());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("correlationId", correlationData.getId());
        detail.put("cause", cause);
        taskFailureService.markFailedIfActive(taskId, "RabbitMQ broker 未确认任务消息：" + nullToUnknown(cause), detail);
    }

    public void handleReturn(ReturnedMessage returnedMessage) {
        Long taskId = extractTaskId(returnedMessage);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("exchange", returnedMessage.getExchange());
        detail.put("routingKey", returnedMessage.getRoutingKey());
        detail.put("replyCode", returnedMessage.getReplyCode());
        detail.put("replyText", returnedMessage.getReplyText());
        detail.put("headers", returnedMessage.getMessage().getMessageProperties().getHeaders());
        taskFailureService.markFailedIfActive(taskId, "RabbitMQ 任务消息未路由到队列：" + returnedMessage.getReplyText(), detail);
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
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
