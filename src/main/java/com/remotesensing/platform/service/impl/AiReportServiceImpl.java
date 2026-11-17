package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.entity.RsAnalysisReport;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsAnalysisReportMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.AiReportService;
import com.remotesensing.platform.vo.AiReportVO;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Owner-scoped access to reports saved before report generation was retired. */
@Service
public class AiReportServiceImpl implements AiReportService {
    private final CurrentUserContext currentUserContext;
    private final RsTaskMapper taskMapper;
    private final RsAnalysisReportMapper analysisReportMapper;
    private final ObjectMapper objectMapper;

    public AiReportServiceImpl(CurrentUserContext users, RsTaskMapper tasks,
                               RsAnalysisReportMapper reports, ObjectMapper json) {
        currentUserContext = users; taskMapper = tasks; analysisReportMapper = reports; objectMapper = json;
    }
    @Override
    public AiReportVO getSavedFromTask(Long taskId) {
        String owner = currentUserContext.getAuthenticatedUserId();
        RsTask task = taskMapper.selectByIdForOwner(taskId, owner);
        if (task == null || !owner.equals(task.getOwnerId())) {
            throw new BusinessException(404, "任务不存在或不可访问");
        }
        RsAnalysisReport report = analysisReportMapper.selectByTaskOwnerAndType(
                reportQuery(task, resolveReportType(task.getTaskType())));
        return report == null ? null : toVO(report, parseReportJson(report.getReportJson()));
    }

    private RsAnalysisReport reportQuery(RsTask task, String reportType) {
        RsAnalysisReport query = new RsAnalysisReport();
        query.setTaskId(task.getId());
        query.setOwnerId(task.getOwnerId());
        query.setReportType(reportType);
        return query;
    }

    private Map<String, Object> parseReportJson(String reportJson) {
        if (reportJson == null || reportJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(reportJson, Map.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 报告 JSON 格式异常");
        }
    }

    private String resolveReportType(String taskType) {
        if (List.of("NDVI", "NDWI", "CHANGE_DETECTION").contains(taskType)) {
            return taskType;
        }
        return "GENERAL";
    }

    /** 实体 -> VO 转换，扁平字段直接映射，reportJson 单独传入。 */
    private AiReportVO toVO(RsAnalysisReport report, Map<String, Object> reportJson) {
        AiReportVO vo = new AiReportVO();
        vo.setId(report.getId());
        vo.setTaskId(report.getTaskId());
        vo.setImageId(report.getImageId());
        vo.setOwnerId(report.getOwnerId());
        vo.setReportType(report.getReportType());
        vo.setSummary(report.getSummary());
        vo.setReportJson(reportJson);
        vo.setCreatedAt(report.getCreatedAt());
        vo.setUpdatedAt(report.getUpdatedAt());
        return vo;
    }

}
