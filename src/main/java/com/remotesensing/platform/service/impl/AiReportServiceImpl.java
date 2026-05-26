package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.common.enums.TaskStatus;
import com.remotesensing.platform.dto.LlmMessage;
import com.remotesensing.platform.entity.RsAnalysisReport;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.entity.RsResultFile;
import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsAnalysisReportMapper;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.mapper.RsResultFileMapper;
import com.remotesensing.platform.mapper.RsTaskMapper;
import com.remotesensing.platform.service.AiReportService;
import com.remotesensing.platform.service.LlmClient;
import com.remotesensing.platform.vo.AiReportVO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 任务结果报告服务实现。
 */
@Service
public class AiReportServiceImpl implements AiReportService {

    private static final String SYSTEM_PROMPT = """
            你是遥感任务结果分析助手。请严格基于输入的 result_metadata 生成中文分析报告。
            只允许输出 JSON，不要 Markdown，不要代码块。
            输出字段固定为：summary, keyFindings, riskLevel, suggestions。
            riskLevel 只允许 LOW、MEDIUM、HIGH、UNKNOWN。
            如果统计信息有限，请明确说明“当前统计信息有限”。
            使用谨慎措辞，例如“可能、建议核验、需结合现场或历史影像进一步确认”。
            不要声称识别了具体地物，除非 result_metadata 明确提供。
            """;

    private final CurrentUserContext currentUserContext;
    private final RsTaskMapper taskMapper;
    private final RsImageMapper imageMapper;
    private final RsResultFileMapper resultFileMapper;
    private final RsAnalysisReportMapper analysisReportMapper;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public AiReportServiceImpl(CurrentUserContext currentUserContext,
                               RsTaskMapper taskMapper,
                               RsImageMapper imageMapper,
                               RsResultFileMapper resultFileMapper,
                               RsAnalysisReportMapper analysisReportMapper,
                               LlmClient llmClient,
                               ObjectMapper objectMapper) {
        this.currentUserContext = currentUserContext;
        this.taskMapper = taskMapper;
        this.imageMapper = imageMapper;
        this.resultFileMapper = resultFileMapper;
        this.analysisReportMapper = analysisReportMapper;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public AiReportVO generateFromTask(Long taskId) {
        String currentUserId = currentUserContext.getCurrentUserId();
        RsTask task = taskMapper.selectByIdForOwner(taskId, currentUserId);
        if (task == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }
        if (TaskStatus.fromDb(task.getStatus()) != TaskStatus.SUCCESS) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务尚未成功，暂无法生成报告");
        }

        RsResultFile resultFile = resultFileMapper.selectByTaskId(taskId);
        if (resultFile == null || isBlank(resultFile.getResultMetadata())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务结果缺少统计元数据，暂无法生成报告");
        }

        RsImage image = task.getImageId() == null ? null : imageMapper.selectById(task.getImageId());
        Map<String, Object> reportJson = generateReportJson(task, resultFile, image);

        RsAnalysisReport report = new RsAnalysisReport();
        report.setTaskId(task.getId());
        report.setImageId(task.getImageId());
        report.setOwnerId(task.getOwnerId());
        report.setReportType(resolveReportType(task.getTaskType()));
        report.setSummary(asString(reportJson.get("summary")));
        report.setReportJson(writeJson(reportJson));
        analysisReportMapper.insert(report);
        return toVO(report, reportJson);
    }

    private Map<String, Object> generateReportJson(RsTask task, RsResultFile resultFile, RsImage image) {
        String userPrompt = buildUserPrompt(task, resultFile, image);
        String content = llmClient.chatJson(List.of(
                new LlmMessage("system", SYSTEM_PROMPT),
                new LlmMessage("user", userPrompt)
        ));
        return parseAndValidateReport(content);
    }

    private String buildUserPrompt(RsTask task, RsResultFile resultFile, RsImage image) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("taskType", task.getTaskType());
        input.put("taskName", task.getTaskName());
        input.put("resultFileName", resultFile.getFileName());
        input.put("resultMetadata", parseMetadata(resultFile.getResultMetadata()));
        if (image != null) {
            input.put("imageName", image.getImageName());
            input.put("sensorType", image.getSensorType());
            input.put("acquisitionTime", image.getAcquisitionTime() == null ? null : image.getAcquisitionTime().toString());
        }
        return writeJson(input);
    }

    private Object parseMetadata(String resultMetadata) {
        try {
            return objectMapper.readValue(resultMetadata, Object.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务结果元数据 JSON 格式异常，暂无法生成报告");
        }
    }

    private Map<String, Object> parseAndValidateReport(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject()) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 报告生成失败，请稍后重试");
            }
            String summary = textOrNull(root.get("summary"));
            if (summary == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 报告生成失败，请稍后重试");
            }
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("summary", summary);
            report.put("keyFindings", stringArray(root.get("keyFindings")));
            report.put("riskLevel", normalizeRiskLevel(root.get("riskLevel")));
            report.put("suggestions", stringArray(root.get("suggestions")));
            return report;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException | java.io.IOException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 报告生成失败，请稍后重试");
        }
    }

    private List<String> stringArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 报告生成失败，请稍后重试");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = textOrNull(item);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private String normalizeRiskLevel(JsonNode node) {
        String riskLevel = textOrNull(node);
        if (riskLevel == null) {
            return "UNKNOWN";
        }
        String normalized = riskLevel.toUpperCase();
        if (!List.of("LOW", "MEDIUM", "HIGH", "UNKNOWN").contains(normalized)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 报告生成失败，请稍后重试");
        }
        return normalized;
    }

    private String resolveReportType(String taskType) {
        if (List.of("NDVI", "NDWI", "CHANGE_DETECTION").contains(taskType)) {
            return taskType;
        }
        return "GENERAL";
    }

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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "AI 报告 JSON 序列化失败");
        }
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
