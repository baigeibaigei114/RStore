package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.entity.MessageOutbox;
import com.remotesensing.platform.mapper.MessageOutboxMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.RsTaskFailureService;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskRecoveryServiceTest {
    @Test void recoversExpiredExecutionAndExhaustedSending() {
        var tasks = mock(RsTaskMapper.class);
        var outbox = mock(MessageOutboxMapper.class);
        var failures = mock(RsTaskFailureService.class);
        var message = new MessageOutbox(); message.setId(5L); message.setAggregateId(2L);
        when(tasks.selectExpiredTasks()).thenReturn(List.of(1L));
        when(outbox.selectExhausted()).thenReturn(List.of(message));
        new TaskRecoveryService(tasks, outbox, failures).recover();
        verify(outbox).exhaustSending(5L);
        verify(failures).markFailedIfActive(eq(1L), contains("租约"), any());
        verify(failures).markFailedIfActive(eq(2L), contains("发布"), any());
    }

    @Test void emptyScansHaveNoSideEffects() {
        var tasks = mock(RsTaskMapper.class);
        var outbox = mock(MessageOutboxMapper.class);
        var failures = mock(RsTaskFailureService.class);
        new TaskRecoveryService(tasks, outbox, failures).recover();
        verifyNoInteractions(failures);
    }
}
