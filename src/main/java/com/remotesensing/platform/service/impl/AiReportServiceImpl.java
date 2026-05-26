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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 任务结果报告服务实现。
 *
 * <p>核心流程分为两个事务阶段：
 * <ol>
 *   <li><b>读取阶段（只读事务）</b>：加载任务、结果文件、影像信息，检查历史报告。
 *   存在历史报告时直接返回（幂等），避免重复调用 LLM；</li>
 *   <li><b>生成和写入阶段（读写事务内双重检查）</b>：调用 LLM 生成报告 JSON，
 *   二次查询确认未重复生成后写入 rs_analysis_report 表。</li>
 * </ol>
 *
 * <p>安全约束：
 * <ul>
 *   <li>LLM 输出四字段 schema 强制校验（summary 必填）；</li>
 *   <li>summary 上限 1000 字符、数组上限 10 项、每项上限 500 字符——三道截断保护；</li>
 *   <li>riskLevel 白名单：LOW / MEDIUM / HIGH / UNKNOWN。</li>
 * </ul>
 */
@Service
public class AiReportServiceImpl implements AiReportService {

    /** 摘要文本最大长度，防止 LLM 生成过长内容撑爆数据库字段。 */
    private static final int SUMMARY_MAX_LENGTH = 1000;
    /** 数组元素最大数量。 */
    private static final int ARRAY_MAX_SIZE = 10;
    /** 数组单个元素最大字符数。 */
    private static final int ARRAY_ITEM_MAX_LENGTH = 500;

    /**
     * 报告生成的 System Prompt。
     *
     * <p>约束要点：
     * <ul>
     *   <li>严格基于输入数据，不编造地物信息——防止 LLM 幻觉产生虚假分析；</li>
     *   <li>措辞谨慎——遥感分析应使用"可能""建议核验"等非确定性表述；</li>
     *   <li>统计信息不足时明确说明，而非强行填补。</li>
     * </ul>
     */
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
    private final TransactionTemplate transactionTemplate;

    public AiReportServiceImpl(CurrentUserContext currentUserContext,
                               RsTaskMapper taskMapper,
                               RsImageMapper imageMapper,
                               RsResultFileMapper resultFileMapper,
                               RsAnalysisReportMapper analysisReportMapper,
                               LlmClient llmClient,
                               ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this.currentUserContext = currentUserContext;
        this.taskMapper = taskMapper;
        this.imageMapper = imageMapper;
        this.resultFileMapper = resultFileMapper;
        this.analysisReportMapper = analysisReportMapper;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 为指定任务生成 AI 分析报告。
     *
     * <p>两阶段事务设计原因：
     * <ul>
     *   <li>LLM 调用耗时长（数秒），不应在事务内持有数据库连接；</li>
     *   <li>第一阶段（只读）快速加载上下文并检查历史报告，存在则幂等返回；</li>
     *   <li>LLM 调用在事务外执行，完成后第二阶段（读写）写入报告。</li>
     * </ul>
     *
     * @param taskId 任务 ID
     * @return 生成（或已有的）分析报告
     * @throws BusinessException 任务不存在/未成功/缺少统计元数据时抛出
     */
    @Override
    public AiReportVO generateFromTask(Long taskId) {
        String currentUserId = currentUserContext.getCurrentUserId();
        ReportContext context = transactionTemplate.execute(status -> loadReportContext(taskId, currentUserId));
        if (context == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务不存在");
        }
        // 历史报告幂等：同一任务+用户+报告类型只生成一次，减少模型调用成本。
        if (context.existingReport() != null) {
            return toVO(context.existingReport(), parseReportJson(context.existingReport().getReportJson()));
        }
        // LLM 调用不持有数据库连接，在事务外执行。
        Map<String, Object> reportJson = generateReportJson(context.task(), context.resultFile(), context.image());
        // 第二阶段：读写事务内双重检查后写入。
        return transactionTemplate.execute(status -> saveReport(context, reportJson));
    }

    /**
     * 第一阶段：只读事务内加载报告生成所需的全部上下文。
     *
     * <p>校验链：任务存在 -> SUCCESS 状态 -> 结果文件有统计元数据 -> 检查历史报告。
     */
    private ReportContext loadReportContext(Long taskId, String currentUserId) {
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

        String reportType = resolveReportType(task.getTaskType());
        RsAnalysisReport query = new RsAnalysisReport();
        query.setTaskId(task.getId());
        query.setOwnerId(task.getOwnerId());
        query.setReportType(reportType);
        RsAnalysisReport existingReport = analysisReportMapper.selectByTaskOwnerAndType(query);
        RsImage image = task.getImageId() == null ? null : imageMapper.selectById(task.getImageId());
        return new ReportContext(task, resultFile, image, reportType, existingReport);
    }

    /**
     * 第二阶段：读写事务内写入报告，写入前再次检查是否有并发生成的报告。
     *
     * <p>双重检查（Double-Check）模式：LLM 调用在事务外，可能存在并发请求
     * 同时通过第一阶段的历史报告检查。在写入事务内再次查询，确保不会重复插入。
     */
    private AiReportVO saveReport(ReportContext context, Map<String, Object> reportJson) {
        RsAnalysisReport latestReport = analysisReportMapper.selectByTaskOwnerAndType(reportQuery(context));
        if (latestReport != null) {
            return toVO(latestReport, parseReportJson(latestReport.getReportJson()));
        }

        RsAnalysisReport report = new RsAnalysisReport();
        report.setTaskId(context.task().getId());
        report.setImageId(context.task().getImageId());
        report.setOwnerId(context.task().getOwnerId());
        report.setReportType(context.reportType());
        report.setSummary(asString(reportJson.get("summary")));
        report.setReportJson(writeJson(reportJson));
        analysisReportMapper.insert(report);
        return toVO(report, reportJson);
    }

    private RsAnalysisReport reportQuery(ReportContext context) {
        RsAnalysisReport query = new RsAnalysisReport();
        query.setTaskId(context.task().getId());
        query.setOwnerId(context.task().getOwnerId());
        query.setReportType(context.reportType());
        return query;
    }

    /**
     * 调用 LLM 生成报告 JSON，不在事务内执行以释放数据库连接。
     */
    private Map<String, Object> generateReportJson(RsTask task, RsResultFile resultFile, RsImage image) {
        String userPrompt = buildUserPrompt(task, resultFile, image);
        String content = llmClient.chatJson(List.of(
                new LlmMessage("system", SYSTEM_PROMPT),
                new LlmMessage("user", userPrompt)
        ));
        return parseAndValidateReport(content);
    }

    /**
     * 构造报告生成的 User Prompt——将任务元数据和统计值 JSON 化传给 LLM。
     */
    private String buildUserPrompt(RsTask task, RsResultFile resultFile, RsImage image) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("taskType", task.getTaskType());
        input.put("resultMetadata", parseMetadata(resultFile.getResultMetadata()));
        if (image != null) {
            input.put("sensorType", image.getSensorType());
            input.put("acquisitionTime", image.getAcquisitionTime() == null ? null : image.getAcquisitionTime().toString());
        }
        return writeJson(input);
    }

    /**
     * 解析结果文件中的统计元数据 JSON，解析失败则拒绝生成（而非传空数据给 LLM）。
     */
    private Object parseMetadata(String resultMetadata) {
        try {
            return objectMapper.readValue(resultMetadata, Object.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "任务结果元数据 JSON 格式异常，暂无法生成报告");
        }
    }

    /**
     * 解析 LLM 返回的报告 JSON，逐字段校验 + 截断保护。
     *
     * <p>四字段 schema：summary（必填，最多 1000 字）、keyFindings（数组，最多 10 项）、
     * riskLevel（白名单）、suggestions（数组，最多 10 项）。
     */
    private Map<String, Object> parseAndValidateReport(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject()) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 报告生成失败，请稍后重试");
            }
            String summary = truncate(textOrNull(root.get("summary")), SUMMARY_MAX_LENGTH);
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

    /**
     * 反序列化已存储的报告 JSON，用于历史报告幂等返回。
     */
    private Map<String, Object> parseReportJson(String reportJson) {
        if (isBlank(reportJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(reportJson, Map.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 报告 JSON 格式异常");
        }
    }

    /**
     * JSON 数组字段解析——带数量截断和单项长度截断双重保护。
     */
    private List<String> stringArray(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 报告生成失败，请稍后重试");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (values.size() >= ARRAY_MAX_SIZE) {
                break;
            }
            String value = textOrNull(item);
            if (value != null) {
                values.add(truncate(value, ARRAY_ITEM_MAX_LENGTH));
            }
        }
        return values;
    }

    /**
     * riskLevel 白名单校验——LLM 可能返回不在允许范围内的值。
     */
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

    /**
     * 任务类型 -> 报告类型映射，未知类型统一归为 GENERAL。
     */
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

    /** 序列化对象为 JSON 字符串，序列化失败视为系统错误。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.FAIL.getCode(), "AI 报告 JSON 序列化失败");
        }
    }

    /** 安全读取 JSON 字符串字段。 */
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

    /** 字符串截断，超过上限部分直接丢弃。 */
    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 报告生成上下文——将第一阶段加载的数据打包传递，避免参数列表过长。
     *
     * @param existingReport 历史报告（非 null 时跳过 LLM 调用，幂等返回）
     */
    private record ReportContext(RsTask task,
                                 RsResultFile resultFile,
                                 RsImage image,
                                 String reportType,
                                 RsAnalysisReport existingReport) {
    }
}
