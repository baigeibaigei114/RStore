package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.service.MessageOutboxService;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消息 Outbox 定时重试调度器。
 *
 * <p>职责：按照固定延迟周期性扫描并重发状态为 {@code PENDING} 或 {@code FAILED} 且到达重试时间的
 * 消息出站记录。归属于 Outbox 模式的消息可靠性保证机制。</p>
 *
 * <p>核心设计点：</p>
 * <ul>
 *   <li>使用 {@link Scheduled @Scheduled} 注解实现周期性调度，无需额外依赖 Quartz；</li>
 *   <li>{@code fixedDelay} 策略——每次执行完成后才等待下一次延迟，避免任务执行时间超过间隔时产生重叠；</li>
 *   <li>延迟时间通过配置 {@code rabbitmq.remote-sensing-task.outbox-retry-delay-ms} 注入，
 *      默认 30 秒；</li>
 *   <li>标注 {@code @Profile("!test")}，单元测试时自动禁用，避免不必要的定时触发。</li>
 * </ul>
 */
@Component
@Profile("!test")
public class MessageOutboxRetryScheduler {

    private final MessageOutboxService messageOutboxService;

    public MessageOutboxRetryScheduler(MessageOutboxService messageOutboxService) {
        this.messageOutboxService = messageOutboxService;
    }

    /**
     * 重试所有到期的待发送消息。
     *
     * <p>调度频率：上一次执行完成后，等待配置的延迟毫秒数（默认 30 秒）再执行下一次。</p>
     *
     * <p>触发条件：应用启动后立即执行第一次，之后按固定延迟持续触发。
     * 被 {@code @Profile("!test")} 限制——test profile 下不加载此 Bean。</p>
     */
    @Scheduled(fixedDelayString = "${rabbitmq.remote-sensing-task.outbox-retry-delay-ms:30000}")
    public void retryPendingMessages() {
        messageOutboxService.publishDueMessages();
    }
}