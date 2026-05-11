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
            // 只有死信信息成功落库后才 ack，避免 DLQ 消息被提前确认而丢失排障线索。
            deadLetterService.record(taskMessage, rawMessage);
            channel.basicAck(deliveryTag, false);
        } catch (Exception exception) {
            // 记录失败时让消息留在死信队列中，等待后续重试或人工排查。
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
