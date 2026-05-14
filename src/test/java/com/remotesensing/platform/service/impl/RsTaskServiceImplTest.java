package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.config.MinioProperties;
import com.remotesensing.platform.config.RabbitTaskProperties;
import com.remotesensing.platform.dto.RsTaskStatusUpdateDTO;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsTaskLogMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.RsTaskFailureService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class RsTaskServiceImplTest {

    @Mock
    private RsImageMapper imageMapper;

    @Mock
    private RsTaskMapper taskMapper;

    @Mock
    private RsTaskLogMapper taskLogMapper;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private RabbitTaskProperties rabbitTaskProperties;

    @Mock
    private MinioProperties minioProperties;

    @Mock
    private RsTaskFailureService taskFailureService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private RsTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new RsTaskServiceImpl(
                imageMapper,
                taskMapper,
                taskLogMapper,
                rabbitTemplate,
                rabbitTaskProperties,
                minioProperties,
                new ObjectMapper(),
                taskFailureService,
                transactionManager
        );
    }

    @Test
    @DisplayName("Worker status callback uses current status guard")
    void updateStatusShouldRejectConcurrentOverwrite() {
        RsTask task = new RsTask();
        task.setId(1L);
        task.setImageId(10L);
        task.setStatus(TaskStatus.RUNNING.dbValue());
        task.setOutputObjectKey("result/NDVI/2026/05/task_1.tif");

        RsTaskStatusUpdateDTO dto = new RsTaskStatusUpdateDTO();
        dto.setStatus(TaskStatus.SUCCESS.dbValue());
        dto.setProgress(100);
        dto.setOutputObjectKey("result/NDVI/2026/05/task_1.tif");

        when(taskMapper.selectById(1L)).thenReturn(task);
        when(taskMapper.updateStatusFromWorker(
                1L,
                TaskStatus.RUNNING.dbValue(),
                TaskStatus.SUCCESS.dbValue(),
                100,
                "result/NDVI/2026/05/task_1.tif",
                null
        )).thenReturn(0);

        assertThatThrownBy(() -> service.updateStatus(1L, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("concurrently");

        verify(taskLogMapper, never()).insert(any());
        verify(imageMapper, never()).markReadyIfProcessing(10L);
    }
}
