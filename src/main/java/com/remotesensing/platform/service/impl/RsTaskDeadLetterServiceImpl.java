package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.dto.RemoteSensingTaskMessage;
import com.remotesensing.platform.service.RsTaskDeadLetterService;
import com.remotesensing.platform.service.RsTaskFailureService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Service;

@Service
public class RsTaskDeadLetterServiceImpl implements RsTaskDeadLetterService {

    private final RsTaskFailureService taskFailureService;

    public RsTaskDeadLetterServiceImpl(RsTaskFailureService taskFailureService) {
        this.taskFailureService = taskFailureService;
    }

    @Override
    public void record(RemoteSensingTaskMessage taskMessage, Message rawMessage) {
        if (taskMessage.getTaskId() == null) {
            return;
        }

        // 消息进入 DLQ 说明 RabbitMQ 已经放弃主队列重试，业务任务也应进入最终失败态。
        taskFailureService.markFailed(
                taskMessage.getTaskId(),
                "任务消息进入死信队列",
                buildDetail(taskMessage, rawMessage)
        );
    }

    private Map<String, Object> buildDetail(RemoteSensingTaskMessage taskMessage, Message rawMessage) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("taskType", taskMessage.getTaskType());
        detail.put("inputBucket", taskMessage.getInputBucket());
        detail.put("inputObjectKey", taskMessage.getInputObjectKey());
        detail.put("outputBucket", taskMessage.getOutputBucket());
        detail.put("outputObjectKey", taskMessage.getOutputObjectKey());
        // headers 中通常包含 x-death，可用于还原失败次数、来源队列和进入死信的原因。
        detail.put("headers", rawMessage.getMessageProperties().getHeaders());
        detail.put("receivedExchange", rawMessage.getMessageProperties().getReceivedExchange());
        detail.put("receivedRoutingKey", rawMessage.getMessageProperties().getReceivedRoutingKey());
        return detail;
    }
}
