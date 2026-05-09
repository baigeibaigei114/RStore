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
        detail.put("headers", rawMessage.getMessageProperties().getHeaders());
        detail.put("receivedExchange", rawMessage.getMessageProperties().getReceivedExchange());
        detail.put("receivedRoutingKey", rawMessage.getMessageProperties().getReceivedRoutingKey());
        return detail;
    }
}
