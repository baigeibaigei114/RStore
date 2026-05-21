package com.remotesensing.platform.service;

import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;

/**
 * RabbitMQ 发布失败处理服务接口。
 * <p>
 * 职责：
 * - 处理消息发布确认回调（ConfirmCallback），处理 Broker 未确认的消息。
 * - 处理消息无法路由回调（ReturnsCallback），处理 mandatory=true 但无绑定队列的消息。
 * <p>
 * 配合 RabbitMqConfig 中配置的确认和返回回调使用。
 */
public interface RabbitPublishFailureService {

    /**
     * 处理消息发布确认结果。
     *
     * @param correlationData 消息关联数据（含消息 ID）
     * @param ack             Broker 是否确认收到消息
     * @param cause           确认失败原因（ack=false 时有效）
     */
    void handleConfirm(CorrelationData correlationData, boolean ack, String cause);

    /**
     * 处理消息无法路由到队列的情况（mandatory=true 且无匹配队列）。
     *
     * @param returnedMessage 返回的消息对象（含路由失败原因）
     */
    void handleReturn(ReturnedMessage returnedMessage);
}
