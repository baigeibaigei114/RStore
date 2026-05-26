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
 */
@Service
public class AiQueryParseServiceImpl implements AiQueryParseService {

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

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

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
