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
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.entity.RsAnalysisReport;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.entity.RsResultFile;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsAnalysisReportMapper;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.LlmClient;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class AiReportServiceImplTest {

    private final CurrentUserContext currentUserContext = Mockito.mock(CurrentUserContext.class);
    private final RsTaskMapper taskMapper = Mockito.mock(RsTaskMapper.class);
    private final RsImageMapper imageMapper = Mockito.mock(RsImageMapper.class);
    private final RsResultFileMapper resultFileMapper = Mockito.mock(RsResultFileMapper.class);
    private final RsAnalysisReportMapper reportMapper = Mockito.mock(RsAnalysisReportMapper.class);
    private final LlmClient llmClient = Mockito.mock(LlmClient.class);
    private final PlatformTransactionManager transactionManager = Mockito.mock(PlatformTransactionManager.class);
    private final AiReportServiceImpl service = new AiReportServiceImpl(
            currentUserContext,
            taskMapper,
            imageMapper,
            resultFileMapper,
            reportMapper,
            llmClient,
            new ObjectMapper(),
            transactionManager
    );

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void generateFromTaskShouldInsertReport() {
        RsTask task = task();
        RsResultFile resultFile = resultFile();
        RsImage image = image();
        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(taskMapper.selectByIdForOwner(10L, "user-a")).thenReturn(task);
        when(resultFileMapper.selectByTaskId(10L)).thenReturn(resultFile);
        when(imageMapper.selectById(3L)).thenReturn(image);
        when(llmClient.chatJson(any())).thenReturn("""
                {
                  "summary": "该区域 NDVI 均值为 0.65，整体植被状况可能较好。",
                  "keyFindings": ["NDVI 均值为 0.65"],
                  "riskLevel": "LOW",
                  "suggestions": ["建议结合历史同期影像进一步核验"]
                }
                """);
        when(reportMapper.insert(any())).thenAnswer(invocation -> {
            RsAnalysisReport report = invocation.getArgument(0);
            report.setId(1L);
            return 1;
        });

        var result = service.generateFromTask(10L);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getTaskId()).isEqualTo(10L);
        assertThat(result.getReportType()).isEqualTo("NDVI");
        assertThat(result.getSummary()).contains("NDVI");
        assertThat(result.getReportJson()).containsEntry("riskLevel", "LOW");

        ArgumentCaptor<RsAnalysisReport> reportCaptor = ArgumentCaptor.forClass(RsAnalysisReport.class);
        verify(reportMapper).insert(reportCaptor.capture());
        assertThat(reportCaptor.getValue().getOwnerId()).isEqualTo("user-a");
        assertThat(reportCaptor.getValue().getReportJson()).contains("keyFindings");
    }

    @Test
    void generateFromTaskShouldRejectOtherUsersTask() {
        when(currentUserContext.getCurrentUserId()).thenReturn("user-b");
        when(taskMapper.selectByIdForOwner(10L, "user-b")).thenReturn(null);

        assertThatThrownBy(() -> service.generateFromTask(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务不存在");

        verify(llmClient, never()).chatJson(any());
    }

    @Test
    void generateFromTaskShouldRejectNonSuccessTask() {
        RsTask task = task();
        task.setStatus(TaskStatus.RUNNING.dbValue());
        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(taskMapper.selectByIdForOwner(10L, "user-a")).thenReturn(task);

        assertThatThrownBy(() -> service.generateFromTask(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("任务尚未成功");

        verify(llmClient, never()).chatJson(any());
    }

    @Test
    void generateFromTaskShouldRejectMissingResultMetadata() {
        RsResultFile resultFile = resultFile();
        resultFile.setResultMetadata(null);
        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(taskMapper.selectByIdForOwner(10L, "user-a")).thenReturn(task());
        when(resultFileMapper.selectByTaskId(10L)).thenReturn(resultFile);

        assertThatThrownBy(() -> service.generateFromTask(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缺少统计元数据");

        verify(llmClient, never()).chatJson(any());
    }

    @Test
    void generateFromTaskShouldReturnExistingReportWithoutCallingLlm() {
        RsAnalysisReport existing = new RsAnalysisReport();
        existing.setId(9L);
        existing.setTaskId(10L);
        existing.setImageId(3L);
        existing.setOwnerId("user-a");
        existing.setReportType("NDVI");
        existing.setSummary("已有报告");
        existing.setReportJson("""
                {"summary":"已有报告","keyFindings":[],"riskLevel":"LOW","suggestions":[]}
                """);
        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(taskMapper.selectByIdForOwner(10L, "user-a")).thenReturn(task());
        when(resultFileMapper.selectByTaskId(10L)).thenReturn(resultFile());
        when(reportMapper.selectByTaskOwnerAndType(any())).thenReturn(existing);

        var result = service.generateFromTask(10L);

        assertThat(result.getId()).isEqualTo(9L);
        assertThat(result.getSummary()).isEqualTo("已有报告");
        verify(llmClient, never()).chatJson(any());
        verify(reportMapper, never()).insert(any());
    }

    @Test
    void generateFromTaskShouldLimitLongAiOutput() {
        when(currentUserContext.getCurrentUserId()).thenReturn("user-a");
        when(taskMapper.selectByIdForOwner(10L, "user-a")).thenReturn(task());
        when(resultFileMapper.selectByTaskId(10L)).thenReturn(resultFile());
        when(imageMapper.selectById(3L)).thenReturn(image());
        when(reportMapper.selectByTaskOwnerAndType(any())).thenReturn(null);
        when(llmClient.chatJson(any())).thenReturn("""
                {
                  "summary": "%s",
                  "keyFindings": ["%s","2","3","4","5","6","7","8","9","10","11"],
                  "riskLevel": "LOW",
                  "suggestions": ["%s"]
                }
                """.formatted("a".repeat(1200), "b".repeat(600), "c".repeat(600)));
        when(reportMapper.insert(any())).thenAnswer(invocation -> {
            RsAnalysisReport report = invocation.getArgument(0);
            report.setId(1L);
            return 1;
        });

        var result = service.generateFromTask(10L);

        assertThat(result.getSummary()).hasSize(1000);
        assertThat((java.util.List<?>) result.getReportJson().get("keyFindings")).hasSize(10);
        assertThat(((java.util.List<?>) result.getReportJson().get("keyFindings")).get(0).toString()).hasSize(500);
        assertThat(((java.util.List<?>) result.getReportJson().get("suggestions")).get(0).toString()).hasSize(500);
    }

    private RsTask task() {
        RsTask task = new RsTask();
        task.setId(10L);
        task.setImageId(3L);
        task.setOwnerId("user-a");
        task.setTaskType("NDVI");
        task.setTaskName("NDVI 处理任务");
        task.setStatus(TaskStatus.SUCCESS.dbValue());
        return task;
    }

    private RsResultFile resultFile() {
        RsResultFile resultFile = new RsResultFile();
        resultFile.setId(100L);
        resultFile.setTaskId(10L);
        resultFile.setImageId(3L);
        resultFile.setFileName("task_10.tif");
        resultFile.setResultMetadata("""
                {"mean":0.65,"lowValuePercent":18}
                """);
        return resultFile;
    }

    private RsImage image() {
        RsImage image = new RsImage();
        image.setId(3L);
        image.setImageName("上海市黄浦区影像");
        image.setSensorType("Sentinel-2");
        image.setAcquisitionTime(OffsetDateTime.parse("2024-05-01T10:00:00+08:00"));
        return image;
    }
}
