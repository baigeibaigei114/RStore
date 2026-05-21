package com.remotesensing.platform.listener;

import com.rabbitmq.client.Channel;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage;
import com.remotesensing.platform.service.RsTaskDeadLetterService;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 死信队列监听器。
 * <p>
 * 职责：
 * - 消费 RabbitMQ 死信队列中的遥感任务消息（超过重试次数的消息）。
 * - 将死信消息记录到数据库，保留排障线索（原始消息内容 + AMQP 属性）。
 * - 只有记录成功后才确认消息，避免丢失排查线索。
 */
@Component
public class RsTaskDeadLetterListener {

    /** 死信记录服务，负责将死信消息持久化到数据库。 */
    private final RsTaskDeadLetterService deadLetterService;

    public RsTaskDeadLetterListener(RsTaskDeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    /**
     * 消费死信队列消息。
     * 只有死信信息成功落库后才 ack，避免 DLQ 消息被提前确认而丢失排障线索。
     */
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
