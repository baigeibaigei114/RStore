package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.config.GeoServerProperties;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.MinioService;
import com.remotesensing.platform.vo.RsTaskVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class GeoServerServiceImplPermissionTest {

    @Mock
    private RestClient restClient;

    @Mock
    private RsTaskMapper taskMapper;

    @Mock
    private RsResultFileMapper resultFileMapper;

    @Mock
    private MinioService minioService;

    @Mock
    private CurrentUserContext currentUserContext;

    private GeoServerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new GeoServerServiceImpl(
                restClient,
                new GeoServerProperties(),
                taskMapper,
                resultFileMapper,
                minioService,
                Runnable::run,
                currentUserContext
        );
    }

    @Test
    @DisplayName("用户不能发布他人的任务结果图层")
    void publishShouldRejectOtherUsersTask() {
        RsTaskVO task = new RsTaskVO();
        task.setId(1L);
        task.setOwnerId("user-a");
        task.setStatus(TaskStatus.SUCCESS.dbValue());
        task.setOutputObjectKey("result/NDVI/2026/05/task_1.tif");

        when(taskMapper.selectDetailById(1L)).thenReturn(task);
        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");

        assertThatThrownBy(() -> service.publishTaskResult(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无权发布");

        verify(resultFileMapper, never()).selectByTaskId(1L);
    }
}
