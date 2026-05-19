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

    @Bean
    public DirectExchange remoteSensingTaskDeadLetterExchange(RabbitTaskProperties properties) {
        return new DirectExchange(properties.getDeadLetterExchange(), true, false);
    }

    @Bean
    public Queue remoteSensingTaskQueue(RabbitTaskProperties properties) {
        return QueueBuilder.durable(properties.getQueue())
                .withArgument("x-dead-letter-exchange", properties.getDeadLetterExchange())
                .withArgument("x-dead-letter-routing-key", properties.getDeadLetterRoutingKey())
                .withArgument("x-queue-type", "quorum")
                .withArgument("x-delivery-limit", properties.getMaxRetryCount())
                .build();
    }

    @Bean
    public Queue remoteSensingTaskDeadLetterQueue(RabbitTaskProperties properties) {
        return QueueBuilder.durable(properties.getDeadLetterQueue()).build();
    }

    @Bean
    public Binding remoteSensingTaskBinding(DirectExchange remoteSensingTaskExchange,
                                            Queue remoteSensingTaskQueue,
                                            RabbitTaskProperties properties) {
        return BindingBuilder.bind(remoteSensingTaskQueue)
                .to(remoteSensingTaskExchange)
                .with(properties.getRoutingKey());
    }

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
