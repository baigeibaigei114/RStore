package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.dto.LlmMessage;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.AiQueryParseService;
import com.remotesensing.platform.service.LlmClient;
import com.remotesensing.platform.vo.AiQueryIntentVO;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 自然语言影像检索解析服务实现。
 *
 * <p>核心流程：将用户自然语言通过 LLM 解析为结构化 JSON，然后逐字段做 Java 侧二次校验。
 * 设计原则是"不信任 LLM 输出"——每个字段都经过类型校验、范围校验、枚举校验，
 * 任意一步不合法即拒绝整个解析结果。这样做比直接透传 LLM 输出到数据库更安全。</p>
 */
@Service
public class AiQueryParseServiceImpl implements AiQueryParseService {

    /**
     * 解析任务的 System Prompt。
     *
     * <p>约束要点：
     * <ul>
     *   <li>输出格式：纯 JSON，禁止 Markdown/代码块/SQL，防止模型自由发挥；</li>
     *   <li>字段限定：六字段白名单，禁止编造不存在的字段；</li>
     *   <li>taskType 枚举闭合：只允许 NDVI/NDWI/CHANGE_DETECTION；</li>
     *   <li>时间格式强约束：ISO-8601 + 时区，便于 Java 端解析。</li>
     * </ul>
     */
    private static final String SYSTEM_PROMPT = """
            你是遥感影像资产检索助手。请把用户自然语言解析为结构化查询 JSON。
            只允许输出 JSON，不要解释，不要 Markdown，不要代码块，不要 SQL。
            输出字段固定为：regionName, startTime, endTime, sensor, maxCloudPercent, taskTypes。
            taskTypes 只允许 NDVI、NDWI、CHANGE_DETECTION。
            无法确定的字段返回 null 或空数组。
            不要编造不存在的字段。
            时间字段使用 ISO-8601 带时区格式，例如 2024-01-01T00:00:00+08:00。
            """;

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    public AiQueryParseServiceImpl(LlmClient llmClient, ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 将自然语言解析为结构化影像检索条件。
     *
     * <p>解析流程：
     * <ol>
     *   <li>构造 system + user 消息，调用 LLM 获取 JSON 字符串；</li>
     *   <li>Jackson 反序列化为 JsonNode（第一层格式校验）；</li>
     *   <li>逐字段类型和范围校验（第二层业务校验）；</li>
     *   <li>组装 {@link AiQueryIntentVO} 返回。</li>
     * </ol>
     * 如果 LLM 返回了合法 JSON 但字段值不在业务允许范围内（如云量 120、taskType=DELETE_IMAGE），
     * 同样拒绝。</p>
     *
     * @param text 用户自然语言查询文本
     * @return 结构化的检索意图
     * @throws BusinessException LLM 返回不可解析内容时抛出
     */
    @Override
    public AiQueryIntentVO parse(String text) {
        String content = llmClient.chatJson(List.of(
                new LlmMessage("system", SYSTEM_PROMPT),
                new LlmMessage("user", text)
        ));
        JsonNode root = parseJson(content);

        AiQueryIntentVO intent = new AiQueryIntentVO();
        intent.setRegionName(textOrNull(root.get("regionName")));
        intent.setStartTime(parseTime(root.get("startTime")));
        intent.setEndTime(parseTime(root.get("endTime")));
        intent.setSensor(textOrNull(root.get("sensor")));
        intent.setMaxCloudPercent(parseCloudPercent(root.get("maxCloudPercent")));
        intent.setTaskTypes(parseTaskTypes(root.get("taskTypes")));
        return intent;
    }

    /**
     * 第一层防线：确保 LLM 返回的是合法 JSON Object。
     */
    private JsonNode parseJson(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isObject()) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 解析失败，请简化查询条件");
            }
            return root;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException | java.io.IOException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 解析失败，请简化查询条件");
        }
    }

    /**
     * 安全读取 JSON 字符串字段，null 和空字符串统一返回 null。
     */
    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * ISO-8601 时间解析 —— 拒绝模糊格式防止数据库写入异常。
     */
    private OffsetDateTime parseTime(JsonNode node) {
        String value = textOrNull(node);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 解析失败，请简化查询条件");
        }
    }

    /**
     * 云量解析 + 范围校验 [0, 100]，防止 LLM 幻觉产生非法值。
     */
    private BigDecimal parseCloudPercent(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        BigDecimal value;
        try {
            value = node.decimalValue();
        } catch (RuntimeException exception) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 解析失败，请简化查询条件");
        }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 解析失败，请简化查询条件");
        }
        return value;
    }

    /**
     * taskType 枚举校验 —— LLM 可能输出不存在的任务类型，必须用枚举兜底。
     */
    private List<TaskType> parseTaskTypes(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 解析失败，请简化查询条件");
        }
        List<TaskType> taskTypes = new ArrayList<>();
        for (JsonNode item : node) {
            String value = textOrNull(item);
            if (value == null) {
                continue;
            }
            try {
                taskTypes.add(TaskType.valueOf(value));
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "AI 解析失败，请简化查询条件");
            }
        }
        return taskTypes;
    }
}
