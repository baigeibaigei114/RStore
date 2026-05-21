package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RabbitMQ 遥感任务队列配置属性，前缀为 "rabbitmq.remote-sensing-task"。
 * <p>
 * 任务队列名称放在配置中，便于不同环境使用不同的 exchange/queue/routingKey。
 * 同时包含发件箱模式的批处理和重试参数。
 */
@Data
@ConfigurationProperties(prefix = "rabbitmq.remote-sensing-task")
public class RabbitTaskProperties {

    /** 遥感任务交换器名称。 */
    private String exchange;

    /** 遥感任务主队列名称。 */
    private String queue;

    /** 路由键，用于将消息从交换器路由到主队列。 */
    private String routingKey;

    /** 死信交换器名称，超过重试次数的消息被转发至此。 */
    private String deadLetterExchange;

    /** 死信队列名称，存储最终失败的消息。 */
    private String deadLetterQueue;

    /** 死信路由键。 */
    private String deadLetterRoutingKey;

    /** 消息最大重试次数，默认 3 次（含首次消费）。 */
    private Integer maxRetryCount = 3;

    /** 发件箱轮询批量处理条数，默认每次 20 条。 */
    private Integer outboxBatchSize = 20;

    /** 消息发送失败后重试延迟（毫秒），默认 30 秒。 */
    private Integer outboxRetryDelayMs = 30000;

    /** 发件箱消息最大重试次数，默认 5 次。 */
    private Integer outboxMaxRetryCount = 5;
}
