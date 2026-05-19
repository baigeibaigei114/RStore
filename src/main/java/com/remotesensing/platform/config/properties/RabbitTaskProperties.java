package com.remotesensing.platform.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rabbitmq.remote-sensing-task")
public class RabbitTaskProperties {

    /**
     * 任务队列名称放在配置中，便于不同环境使用不同的 exchange/queue/routingKey。
     */
    private String exchange;
    private String queue;
    private String routingKey;
    private String deadLetterExchange;
    private String deadLetterQueue;
    private String deadLetterRoutingKey;
    private Integer maxRetryCount = 3;
    private Integer outboxBatchSize = 20;
    private Integer outboxRetryDelayMs = 30000;
    private Integer outboxMaxRetryCount = 5;
}
