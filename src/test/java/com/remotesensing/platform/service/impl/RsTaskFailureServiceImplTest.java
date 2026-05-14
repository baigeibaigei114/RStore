package com.remotesensing.platform.service.impl;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsTaskLogMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RsTaskFailureServiceImplTest {

    @Mock
    private RsTaskMapper taskMapper;

    @Mock
    private RsImageMapper imageMapper;

    @Mock
    private RsTaskLogMapper taskLogMapper;

    private RsTaskFailureServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RsTaskFailureServiceImpl(taskMapper, imageMapper, taskLogMapper, new ObjectMapper());
    }

    @Test
    @DisplayName("failure fallback does not overwrite SUCCESS task")
    void markFailedIfActiveShouldNotOverwriteSuccessTask() {
        RsTask task = new RsTask();
        task.setId(1L);
        task.setImageId(10L);
        task.setStatus(TaskStatus.SUCCESS.dbValue());

        when(taskMapper.selectById(1L)).thenReturn(task);
        when(taskMapper.markFailedIfActive(1L, "publish failed")).thenReturn(0);

        service.markFailedIfActive(1L, "publish failed", Map.of("source", "test"));

        verify(taskMapper).markFailedIfActive(1L, "publish failed");
        verify(taskLogMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(imageMapper, never()).markReadyIfProcessing(org.mockito.ArgumentMatchers.anyLong());
    }
}
