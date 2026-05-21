package com.remotesensing.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.config.properties.RabbitTaskProperties;
import com.remotesensing.platform.service.RabbitPublishFailureService;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 消息队列配置类。
 * <p>
 * 职责：
 * - 声明遥感任务交换器、队列及其死信交换器/队列。
 * - 使用 Quorum 队列提供高可用性，通过死信机制处理消费失败的消息。
 * - 配置 JSON 消息转换器，便于 Java 后端与 Python Worker 之间解耦。
 * - 配置 RabbitTemplate 的 ConfirmCallback 和 ReturnsCallback，确保消息可靠投递。
 */
@Configuration
@EnableConfigurationProperties(RabbitTaskProperties.class)
public class RabbitMqConfig {

    /**
     * 任务交换机、队列均声明为持久化，避免 RabbitMQ 重启后任务入口丢失。
     */
    @Bean
    public DirectExchange remoteSensingTaskExchange(RabbitTaskProperties properties) {
        return new DirectExchange(properties.getExchange(), true, false);
    }

    /**
     * 死信交换机，用于接收超过重试次数上限的任务消息。
     */
    @Bean
    public DirectExchange remoteSensingTaskDeadLetterExchange(RabbitTaskProperties properties) {
        return new DirectExchange(properties.getDeadLetterExchange(), true, false);
    }

    /**
     * 遥感任务主队列。
     * - 使用 Quorum 队列类型，提供数据一致性和高可用。
     * - 绑定死信交换器，消息被拒绝或超时后转发至 DLQ。
     * - x-delivery-limit 控制最大重试次数，超过后进入死信队列。
     */
    @Bean
    public Queue remoteSensingTaskQueue(RabbitTaskProperties properties) {
        return QueueBuilder.durable(properties.getQueue())
                .withArgument("x-dead-letter-exchange", properties.getDeadLetterExchange())
                .withArgument("x-dead-letter-routing-key", properties.getDeadLetterRoutingKey())
                .withArgument("x-queue-type", "quorum")
                .withArgument("x-delivery-limit", properties.getMaxRetryCount())
                .build();
    }

    /**
     * 死信队列，存储所有重试耗尽后仍失败的消息，供人工排查或后续补偿处理。
     */
    @Bean
    public Queue remoteSensingTaskDeadLetterQueue(RabbitTaskProperties properties) {
        return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
    }

    /** 将主队列绑定到主交换器，路由键由配置指定。 */
    @Bean
    public Binding remoteSensingTaskBinding(DirectExchange remoteSensingTaskExchange,
                                            Queue remoteSensingTaskQueue,
                                            RabbitTaskProperties properties) {
        return BindingBuilder.bind(remoteSensingTaskQueue)
                .to(remoteSensingTaskExchange)
                .with(properties.getRoutingKey());
    }

    /** 将死信队列绑定到死信交换器，路由键由配置指定。 */
    @Bean
    public Binding remoteSensingTaskDeadLetterBinding(DirectExchange remoteSensingTaskDeadLetterExchange,
                                                      Queue remoteSensingTaskDeadLetterQueue,
                                                      RabbitTaskProperties properties) {
        return BindingBuilder.bind(remoteSensingTaskDeadLetterQueue)
                .to(remoteSensingTaskDeadLetterExchange)
                .with(properties.getDeadLetterRoutingKey());
    }

    /**
     * 统一使用 JSON 消息，方便 Java 后端和 Python worker 之间解耦。
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * 配置 RabbitTemplate：
     * - 设置 JSON 消息转换器。
     * - 启用 mandatory（无法路由时触发 ReturnsCallback）。
     * - 注册 ConfirmCallback 处理发布确认。
     * - 注册 ReturnsCallback 处理不可路由消息。
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter,
                                         RabbitPublishFailureService publishFailureService) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter);
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback(publishFailureService::handleConfirm);
        rabbitTemplate.setReturnsCallback(publishFailureService::handleReturn);
        return rabbitTemplate;
    }
}
