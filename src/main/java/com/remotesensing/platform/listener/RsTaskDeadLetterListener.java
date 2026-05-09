package com.remotesensing.platform.listener;

import com.rabbitmq.client.Channel;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage;
import com.remotesensing.platform.service.RsTaskDeadLetterService;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RsTaskDeadLetterListener {

    private final RsTaskDeadLetterService deadLetterService;

    public RsTaskDeadLetterListener(RsTaskDeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @RabbitListener(queues = "${rabbitmq.remote-sensing-task.dead-letter-queue}")
    public void handleDeadLetter(RemoteSensingTaskMessage taskMessage, Message rawMessage, Channel channel)
            throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
        try {
            deadLetterService.record(taskMessage, rawMessage);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
