package com.remotesensing.platform.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.remotesensing.platform.config.RabbitTaskProperties;
import com.remotesensing.platform.entity.MessageOutbox;
import com.remotesensing.platform.mapper.MessageOutboxMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;

@ExtendWith(MockitoExtension.class)
class RabbitPublishFailureServiceTest {

    @Mock
    private MessageOutboxMapper outboxMapper;

    @Mock
    private RsTaskFailureService taskFailureService;

    @Mock
    private RabbitTaskProperties rabbitTaskProperties;

    private RabbitPublishFailureService service;

    @BeforeEach
    void setUp() {
        service = new RabbitPublishFailureService(outboxMapper, taskFailureService, rabbitTaskProperties);
    }

    @Test
    @DisplayName("Confirm ack 只按 outboxId 标记 SENDING 为 SENT")
    void confirmAckShouldMarkSendingOutboxSentByOutboxId() {
        service.handleConfirm(new CorrelationData("99"), true, null);

        verify(outboxMapper).markSentIfSending(99L);
        verify(outboxMapper, never()).markSentIfNotFailed(any());
    }

    @Test
    @DisplayName("Return 后按 outboxId 标记失败且不会被任务 id 粗粒度更新")
    void returnedMessageShouldMarkOutboxFailedByOutboxId() {
        MessageOutbox outbox = outbox(99L, 1L, 1, 5);
        when(outboxMapper.selectById(99L)).thenReturn(outbox, outbox);
        when(rabbitTaskProperties.getOutboxRetryDelayMs()).thenReturn(30000);
        when(outboxMapper.markFailedIfSending(eq(99L), contains("NO_ROUTE"), any(OffsetDateTime.class)))
                .thenReturn(1);

        service.handleReturn(returnedMessage(99L, 1L));

        verify(outboxMapper).markFailedIfSending(eq(99L), contains("NO_ROUTE"), any(OffsetDateTime.class));
        verify(outboxMapper, never()).markSentIfNotFailed(any());
        verify(taskFailureService, never()).markFailedIfActive(any(), any(), any());
    }

    @Test
    @DisplayName("Return 已标记 FAILED 后 Confirm ack 不能覆盖为 SENT")
    void confirmAckAfterReturnShouldNotUseFailedToSentUpdate() {
        service.handleConfirm(new CorrelationData("99"), true, null);

        verify(outboxMapper).markSentIfSending(99L);
        verify(outboxMapper, never()).markSentIfNotFailed(99L);
    }

    @Test
    @DisplayName("Return 失败更新未命中时不触发任务最终失败")
    void returnedMessageShouldNotFailTaskWhenOutboxStateChanged() {
        MessageOutbox outbox = outbox(99L, 1L, 5, 5);
        when(outboxMapper.selectById(99L)).thenReturn(outbox);
        when(rabbitTaskProperties.getOutboxRetryDelayMs()).thenReturn(30000);
        when(outboxMapper.markFailedIfSending(eq(99L), contains("NO_ROUTE"), any(OffsetDateTime.class)))
                .thenReturn(0);

        service.handleReturn(returnedMessage(99L, 1L));

        verify(taskFailureService, never()).markFailedIfActive(any(), any(), any());
    }

    private ReturnedMessage returnedMessage(Long outboxId, Long taskId) {
        Message message = MessageBuilder.withBody("{}".getBytes())
                .setHeader("outboxId", outboxId)
                .setHeader("taskId", taskId)
                .build();
        return new ReturnedMessage(message, 312, "NO_ROUTE", "rs.task.exchange", "missing.routing.key");
    }

    private MessageOutbox outbox(Long id, Long taskId, int retryCount, int maxRetryCount) {
        MessageOutbox outbox = new MessageOutbox();
        outbox.setId(id);
        outbox.setAggregateId(taskId);
        outbox.setRetryCount(retryCount);
        outbox.setMaxRetryCount(maxRetryCount);
        return outbox;
    }
}
