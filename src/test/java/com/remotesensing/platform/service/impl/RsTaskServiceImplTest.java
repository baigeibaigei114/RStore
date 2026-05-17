package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.enums.ImageStatus;
import com.remotesensing.platform.common.enums.ResultFileStatus;
import com.remotesensing.platform.common.enums.Visibility;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.config.MinioProperties;
import com.remotesensing.platform.config.RabbitTaskProperties;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage;
import com.remotesensing.platform.dto.RsTaskStatusUpdateDTO;
import com.remotesensing.platform.dto.RsTaskSubmitDTO;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.entity.RsResultFile;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskLogMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.GeoServerService;
import com.remotesensing.platform.service.MessageOutboxService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
    private RsResultFileMapper resultFileMapper;

    @Mock
    private RabbitTaskProperties rabbitTaskProperties;

    @Mock
    private MinioProperties minioProperties;

    @Mock
    private MessageOutboxService messageOutboxService;

    @Mock
    private GeoServerService geoServerService;

    @Mock
    private CurrentUserContext currentUserContext;

    @Mock
    private PlatformTransactionManager transactionManager;

    private RsTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        service = new RsTaskServiceImpl(
                imageMapper,
                taskMapper,
                taskLogMapper,
                resultFileMapper,
                rabbitTaskProperties,
                minioProperties,
                new ObjectMapper(),
                messageOutboxService,
                geoServerService,
                transactionManager,
                currentUserContext
        );
    }

    @Test
    @DisplayName("提交任务时创建 Outbox 消息并尝试立即投递")
    void submitShouldCreateOutboxAndPublishAfterCommit() {
        RsImage image = new RsImage();
        image.setId(10L);
        image.setStatus(ImageStatus.READY.dbValue());
        image.setVisibility(Visibility.PRIVATE.dbValue());
        image.setOwnerId("user-a");
        image.setMinioBucket("remote-sensing-images");
        image.setObjectKey("raw/2026/05/source.tif");

        RsTaskSubmitDTO dto = new RsTaskSubmitDTO();
        dto.setImageId(10L);
        dto.setTaskType(RemoteSensingTaskMessage.TaskType.NDVI);
        dto.setParams(Map.of("redBand", 3, "nirBand", 4));

        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(imageMapper.selectAccessibleById(10L, "user-a")).thenReturn(image);
        when(imageMapper.markProcessingIfReady(10L)).thenReturn(1);
        when(rabbitTaskProperties.getMaxRetryCount()).thenReturn(3);
        when(minioProperties.getBucketName()).thenReturn("remote-sensing-images");
        when(messageOutboxService.createTaskMessage(any(), any())).thenReturn(99L);
        when(taskMapper.insert(any(RsTask.class))).thenAnswer(this::fillTaskId);

        service.submit(dto);

        verify(messageOutboxService).createTaskMessage(org.mockito.ArgumentMatchers.eq(1L), any(RemoteSensingTaskMessage.class));
        verify(messageOutboxService).publishById(99L);
    }

    @Test
    @DisplayName("用户不能提交无权访问的私有影像任务")
    void submitShouldRejectPrivateImageWithoutAccess() {
        RsTaskSubmitDTO dto = new RsTaskSubmitDTO();
        dto.setImageId(10L);
        dto.setTaskType(RemoteSensingTaskMessage.TaskType.NDVI);
        dto.setParams(Map.of("redBand", 3, "nirBand", 4));

        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(imageMapper.selectAccessibleById(10L, "user-b")).thenReturn(null);

        assertThatThrownBy(() -> service.submit(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权访问");

        verify(taskMapper, never()).insert(any(RsTask.class));
    }

    @Test
    @DisplayName("用户可以提交他人 PUBLIC 影像任务，任务归属当前用户")
    void submitShouldAllowPublicImageAndOwnTask() {
        RsImage image = new RsImage();
        image.setId(10L);
        image.setStatus(ImageStatus.READY.dbValue());
        image.setVisibility(Visibility.PUBLIC.dbValue());
        image.setOwnerId("user-a");
        image.setMinioBucket("remote-sensing-images");
        image.setObjectKey("raw/2026/05/public.tif");

        RsTaskSubmitDTO dto = new RsTaskSubmitDTO();
        dto.setImageId(10L);
        dto.setTaskType(RemoteSensingTaskMessage.TaskType.NDVI);
        dto.setParams(Map.of("redBand", 3, "nirBand", 4));

        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(imageMapper.selectAccessibleById(10L, "user-b")).thenReturn(image);
        when(imageMapper.markProcessingIfReady(10L)).thenReturn(1);
        when(rabbitTaskProperties.getMaxRetryCount()).thenReturn(3);
        when(minioProperties.getBucketName()).thenReturn("remote-sensing-images");
        when(messageOutboxService.createTaskMessage(any(), any())).thenReturn(99L);
        when(taskMapper.insert(any(RsTask.class))).thenAnswer(this::fillTaskId);

        service.submit(dto);

        verify(taskMapper).insert(org.mockito.ArgumentMatchers.argThat(task -> "user-b".equals(task.getOwnerId())));
    }

    @Test
    @DisplayName("用户不能查看他人的任务详情")
    void getByIdShouldRejectOtherUsersTask() {
        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(taskMapper.selectDetailByIdForOwner(1L, "user-b")).thenReturn(null);

        assertThatThrownBy(() -> service.getById(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务不存在");
    }

    @Test
    @DisplayName("用户只能查询自己任务的结果文件")
    void getResultFileShouldCheckTaskOwner() {
        RsTask task = new RsTask();
        task.setId(1L);
        task.setOwnerId("user-a");
        task.setStatus(TaskStatus.SUCCESS.dbValue());

        RsResultFile resultFile = new RsResultFile();
        resultFile.setId(10L);
        resultFile.setOwnerId("user-a");
        resultFile.setTaskId(1L);
        resultFile.setImageId(2L);
        resultFile.setObjectKey("result/NDVI/2026/05/task_1.tif");
        resultFile.setStatus(ResultFileStatus.PUBLISHED.dbValue());
        resultFile.setLayerName("rs_task_1");
        resultFile.setWmsUrl("http://localhost:8081/geoserver/remote_sensing/wms?layers=remote_sensing:rs_task_1");

        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(taskMapper.selectByIdForOwner(1L, "user-a")).thenReturn(task);
        when(resultFileMapper.selectByTaskId(1L)).thenReturn(resultFile);

        assertThat(service.getResultFile(1L).getObjectKey())
                .isEqualTo("result/NDVI/2026/05/task_1.tif");
    }

    @Test
    @DisplayName("任务无归属权限时不能查询结果文件")
    void getResultFileShouldRejectOtherUsersTask() {
        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(taskMapper.selectByIdForOwner(1L, "user-b")).thenReturn(null);

        assertThatThrownBy(() -> service.getResultFile(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务不存在");

        verify(resultFileMapper, never()).selectByTaskId(any());
    }

    @Test
    @DisplayName("Worker 状态回调使用当前状态保护")
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
                .hasMessageContaining("并发更新");

        verify(taskLogMapper, never()).insert(any());
        verify(imageMapper, never()).markReadyIfProcessing(10L);
    }

    @Test
    @DisplayName("Worker 回调 RETRYING 时应保留失败原因")
    void updateRetryingStatusShouldKeepFailureReason() {
        RsTask task = new RsTask();
        task.setId(1L);
        task.setImageId(10L);
        task.setStatus(TaskStatus.RUNNING.dbValue());
        task.setOutputObjectKey("result/NDVI/2026/05/task_1.tif");

        RsTaskStatusUpdateDTO dto = new RsTaskStatusUpdateDTO();
        dto.setStatus(TaskStatus.RETRYING.dbValue());
        dto.setMessage("波段编号 4 超出范围，当前影像共有 2 个波段");

        when(taskMapper.selectById(1L)).thenReturn(task);
        when(taskMapper.updateStatusFromWorker(
                1L,
                TaskStatus.RUNNING.dbValue(),
                TaskStatus.RETRYING.dbValue(),
                null,
                null,
                "波段编号 4 超出范围，当前影像共有 2 个波段"
        )).thenReturn(1);

        service.updateStatus(1L, dto);

        verify(taskLogMapper).insert(org.mockito.ArgumentMatchers.argThat(log ->
                "INFO".equals(log.getLogLevel())
                        && log.getMessage().contains("波段编号 4 超出范围")
                        && log.getDetail().contains("波段编号 4 超出范围")
        ));
        verify(imageMapper, never()).markReadyIfProcessing(10L);
    }

    private int fillTaskId(InvocationOnMock invocation) {
        RsTask task = invocation.getArgument(0);
        task.setId(1L);
        return 1;
    }
}
