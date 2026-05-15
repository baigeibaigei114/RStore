package com.remotesensing.platform.service.impl;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.enums.MessageOutboxStatus;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.config.RabbitTaskProperties;
import com.remotesensing.platform.entity.MessageOutbox;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.mapper.MessageOutboxMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.RsTaskFailureService;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class MessageOutboxServiceImplTest {

    @Mock
    private MessageOutboxMapper outboxMapper;

    @Mock
    private RsTaskMapper taskMapper;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RabbitTaskProperties rabbitTaskProperties;

    @Mock
    private RsTaskFailureService taskFailureService;

    private MessageOutboxServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MessageOutboxServiceImpl(
                outboxMapper,
                taskMapper,
                rabbitTemplate,
                rabbitTaskProperties,
                new ObjectMapper(),
                taskFailureService
        );
    }

    @Test
    @DisplayName("终态任务跳过 Outbox 投递")
    void publishByIdShouldSkipTerminalTask() {
        MessageOutbox outbox = new MessageOutbox();
        outbox.setId(99L);
        outbox.setAggregateId(1L);
        outbox.setStatus(MessageOutboxStatus.PENDING.dbValue());
        outbox.setPayload("{}");
        outbox.setNextRetryAt(OffsetDateTime.now());

        RsTask task = new RsTask();
        task.setId(1L);
        task.setStatus(TaskStatus.SUCCESS.dbValue());

        when(outboxMapper.selectById(99L)).thenReturn(outbox);
        when(taskMapper.selectById(1L)).thenReturn(task);

        service.publishById(99L);

        verify(outboxMapper).markSentByTaskId(1L);
        verify(outboxMapper, never()).markPublishAttempt(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verifyNoInteractions(rabbitTemplate);
    }
}
