package com.remotesensing.platform.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.entity.RsAnalysisReport;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsAnalysisReportMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import org.junit.jupiter.api.Test;

class AiReportServiceImplTest {
    private final CurrentUserContext users=mock(CurrentUserContext.class);
    private final RsTaskMapper tasks=mock(RsTaskMapper.class);
    private final RsAnalysisReportMapper reports=mock(RsAnalysisReportMapper.class);
    private final AiReportServiceImpl service=new AiReportServiceImpl(users,tasks,reports,new ObjectMapper());

    private void ownedTask() {
        when(users.getAuthenticatedUserId()).thenReturn("owner");
        RsTask task=new RsTask(); task.setId(7L); task.setOwnerId("owner"); task.setTaskType("NDVI");
        when(tasks.selectByIdForOwner(7L,"owner")).thenReturn(task);
    }
    @Test void readsSavedReportWithoutRequiringASuccessfulCurrentResult() {
        ownedTask(); RsAnalysisReport saved=new RsAnalysisReport(); saved.setTaskId(7L); saved.setSummary("历史摘要");
        saved.setReportJson("{}"); when(reports.selectByTaskOwnerAndType(any())).thenReturn(saved);
        assertThat(service.getSavedFromTask(7L).getSummary()).isEqualTo("历史摘要");
        verify(reports).selectByTaskOwnerAndType(argThat(query -> query.getTaskId()==7L
                && "owner".equals(query.getOwnerId()) && "NDVI".equals(query.getReportType())));
    }
    @Test void absentReportReturnsNullWithoutGenerating() { ownedTask(); assertThat(service.getSavedFromTask(7L)).isNull(); }
    @Test void deniesOtherOwnerBeforeReadingSavedReport() {
        when(users.getAuthenticatedUserId()).thenReturn("other");
        assertThatThrownBy(() -> service.getSavedFromTask(7L)).isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException)error).getCode()).isEqualTo(404));
        verifyNoInteractions(reports);
    }
    @Test void malformedSavedJsonFailsExplicitly() {
        ownedTask(); RsAnalysisReport saved=new RsAnalysisReport(); saved.setReportJson("invalid");
        when(reports.selectByTaskOwnerAndType(any())).thenReturn(saved);
        assertThatThrownBy(() -> service.getSavedFromTask(7L)).isInstanceOf(BusinessException.class).hasMessage("AI 报告 JSON 格式异常");
    }
}
