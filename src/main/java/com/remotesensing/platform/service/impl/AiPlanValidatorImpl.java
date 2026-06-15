package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.enums.AiPlanStepType;
import com.remotesensing.platform.dto.AiPlanContentDTO;
import com.remotesensing.platform.dto.AiPlanStepDTO;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import com.remotesensing.platform.service.AiPlanValidator;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * AI Plan 校验实现，负责兜住 LLM 输出边界。
 */
@Service
public class AiPlanValidatorImpl implements AiPlanValidator {

    private static final Set<String> SEARCH_IMAGE_PARAMS = Set.of(
            "regionName", "regionId", "bbox", "startTime", "endTime", "sensor", "maxCloudPercent", "keyword"
    );
    private static final Set<String> SUBMIT_TASK_PARAMS = Set.of("taskType", "imageId");
    private static final Set<String> GENERATE_REPORT_PARAMS = Set.of("taskId", "reportType");
    private static final Set<String> REPORT_TYPES = Set.of("NDVI", "NDWI", "CHANGE_DETECTION", "GENERAL");
    private static final int MAX_STEPS = 10;
    private static final int MAX_GOAL_LENGTH = 300;
    private static final int MAX_DESCRIPTION_LENGTH = 500;
    private static final int MAX_PARAMS_SIZE = 10;
    private static final int MAX_PARAM_STRING_LENGTH = 300;

    @Override
    public List<String> validate(AiPlanContentDTO plan) {
        List<String> errors = new ArrayList<>();
        if (plan == null) {
            errors.add("plan 不能为空");
            return errors;
        }
        if (plan.getSteps() == null || plan.getSteps().isEmpty()) {
            errors.add("steps 不能为空");
            return errors;
        }
        if (plan.getSteps().size() > MAX_STEPS) {
            errors.add("steps 不能超过 " + MAX_STEPS + " 个");
        }
        if (stringValue(plan.getGoal()) != null && plan.getGoal().length() > MAX_GOAL_LENGTH) {
            errors.add("goal 长度不能超过 " + MAX_GOAL_LENGTH);
        }

        for (int index = 0; index < plan.getSteps().size(); index++) {
            AiPlanStepDTO step = plan.getSteps().get(index);
            validateCommonStep(step, index + 1, errors);
            if (step == null || !AiPlanStepType.isSupported(step.getType())) {
                continue;
            }
            AiPlanStepType type = AiPlanStepType.fromValue(step.getType());
            validateStepByType(type, step, errors);
        }
        return errors;
    }

    private void validateCommonStep(AiPlanStepDTO step, int expectedOrder, List<String> errors) {
        if (step == null) {
            errors.add("第 " + expectedOrder + " 个步骤不能为空");
            return;
        }
        if (step.getOrder() == null || step.getOrder() != expectedOrder) {
            errors.add("步骤 order 必须从 1 开始连续递增");
        }
        if (!AiPlanStepType.isSupported(step.getType())) {
            errors.add("不支持的步骤类型：" + step.getType());
        }
        if (step.getDescription() == null || step.getDescription().isBlank()) {
            errors.add("步骤 " + expectedOrder + " description 不能为空");
        } else if (step.getDescription().length() > MAX_DESCRIPTION_LENGTH) {
            errors.add("步骤 " + expectedOrder + " description 长度不能超过 " + MAX_DESCRIPTION_LENGTH);
        }
        if (step.getRequiresConfirmation() == null) {
            errors.add("步骤 " + expectedOrder + " requiresConfirmation 不能为空");
        }
        Map<String, Object> params = params(step);
        if (params.size() > MAX_PARAMS_SIZE) {
            errors.add("步骤 " + expectedOrder + " params 不能超过 " + MAX_PARAMS_SIZE + " 个");
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            validateParamSize(expectedOrder, entry.getKey(), entry.getValue(), errors);
        }
    }

    private void validateStepByType(AiPlanStepType type, AiPlanStepDTO step, List<String> errors) {
        Map<String, Object> params = params(step);
        rejectSqlLikeParams(step.getOrder(), params, errors);
        if (type.requiresUserConfirmation() && !Boolean.TRUE.equals(step.getRequiresConfirmation())) {
            errors.add(type.name() + " 步骤必须 requiresConfirmation=true");
        }

        switch (type) {
            case RESOLVE_REGION -> validateResolveRegion(step, params, errors);
            case SEARCH_IMAGES -> validateSearchImages(params, errors);
            case SUBMIT_TASK -> validateSubmitTask(params, errors);
            case GENERATE_REPORT -> validateGenerateReport(params, errors);
            case SELECT_IMAGE, DOWNLOAD_RESULT, WAIT_TASK, LIST_LAYERS -> {
                // 当前版本只校验通用字段和高风险确认标记。
            }
        }
    }

    private void validateResolveRegion(AiPlanStepDTO step, Map<String, Object> params, List<String> errors) {
        if (isBlankValue(params.get("regionName")) && isBlankValue(params.get("regionId"))) {
            errors.add("RESOLVE_REGION 需要 regionName 或 regionId");
        }
    }

    private void validateSearchImages(Map<String, Object> params, List<String> errors) {
        rejectUnknownParams("SEARCH_IMAGES", params, SEARCH_IMAGE_PARAMS, errors);
        validateCloudPercent(params.get("maxCloudPercent"), errors);
        validateTimeRange(params.get("startTime"), params.get("endTime"), errors);
        validateBbox(params.get("bbox"), errors);
    }

    private void validateSubmitTask(Map<String, Object> params, List<String> errors) {
        rejectUnknownParams("SUBMIT_TASK", params, SUBMIT_TASK_PARAMS, errors);
        String taskType = stringValue(params.get("taskType"));
        if (taskType == null) {
            errors.add("SUBMIT_TASK 需要 taskType");
        } else {
            try {
                TaskType.valueOf(taskType);
            } catch (IllegalArgumentException exception) {
                errors.add("SUBMIT_TASK taskType 只能是 NDVI、NDWI、CHANGE_DETECTION");
            }
        }
        validatePositiveInteger(params.get("imageId"), "SUBMIT_TASK imageId", errors);
    }

    private void validateGenerateReport(Map<String, Object> params, List<String> errors) {
        rejectUnknownParams("GENERATE_REPORT", params, GENERATE_REPORT_PARAMS, errors);
        String reportType = stringValue(params.get("reportType"));
        if (reportType != null && !REPORT_TYPES.contains(reportType)) {
            errors.add("GENERATE_REPORT reportType 只能是 NDVI、NDWI、CHANGE_DETECTION、GENERAL");
        }
        validatePositiveInteger(params.get("taskId"), "GENERATE_REPORT taskId", errors);
    }

    private void validateCloudPercent(Object value, List<String> errors) {
        if (value == null) {
            return;
        }
        try {
            BigDecimal cloud = new BigDecimal(value.toString());
            if (cloud.compareTo(BigDecimal.ZERO) < 0 || cloud.compareTo(new BigDecimal("100")) > 0) {
                errors.add("maxCloudPercent 必须在 0 到 100 之间");
            }
        } catch (RuntimeException exception) {
            errors.add("maxCloudPercent 必须是数字");
        }
    }

    private void validateTimeRange(Object startValue, Object endValue, List<String> errors) {
        OffsetDateTime start = parseTime(startValue, "startTime", errors);
        OffsetDateTime end = parseTime(endValue, "endTime", errors);
        if (start != null && end != null && start.isAfter(end)) {
            errors.add("startTime 不能晚于 endTime");
        }
    }

    private OffsetDateTime parseTime(Object value, String fieldName, List<String> errors) {
        String text = stringValue(value);
        if (text == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException exception) {
            errors.add(fieldName + " 必须是 ISO-8601 时间格式");
            return null;
        }
    }

    private void validateBbox(Object value, List<String> errors) {
        if (value == null) {
            return;
        }
        List<BigDecimal> numbers = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                addDecimal(item, numbers, errors);
            }
        } else {
            String text = stringValue(value);
            if (text != null) {
                for (String item : text.split(",")) {
                    addDecimal(item.trim(), numbers, errors);
                }
            }
        }
        if (numbers.size() != 4) {
            errors.add("bbox 必须包含 minLng,minLat,maxLng,maxLat 四个值");
            return;
        }
        BigDecimal minLng = numbers.get(0);
        BigDecimal minLat = numbers.get(1);
        BigDecimal maxLng = numbers.get(2);
        BigDecimal maxLat = numbers.get(3);
        if (minLng.compareTo(maxLng) >= 0 || minLat.compareTo(maxLat) >= 0) {
            errors.add("bbox 最小经纬度必须小于最大经纬度");
        }
        if (minLng.compareTo(new BigDecimal("-180")) < 0 || maxLng.compareTo(new BigDecimal("180")) > 0) {
            errors.add("bbox 经度范围必须在 [-180,180]");
        }
        if (minLat.compareTo(new BigDecimal("-90")) < 0 || maxLat.compareTo(new BigDecimal("90")) > 0) {
            errors.add("bbox 纬度范围必须在 [-90,90]");
        }
    }

    private void addDecimal(Object value, List<BigDecimal> numbers, List<String> errors) {
        try {
            numbers.add(new BigDecimal(value.toString()));
        } catch (RuntimeException exception) {
            errors.add("bbox 必须是数字");
        }
    }

    private void rejectUnknownParams(String stepType, Map<String, Object> params, Set<String> allowed, List<String> errors) {
        for (String key : params.keySet()) {
            if (!allowed.contains(key)) {
                errors.add(stepType + " 不支持参数：" + key);
            }
        }
    }

    private void rejectSqlLikeParams(Integer order, Map<String, Object> params, List<String> errors) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String value = stringValue(entry.getValue());
            if (value != null && looksLikeSql(value)) {
                errors.add("步骤 " + order + " 参数 " + entry.getKey() + " 包含不允许的 SQL 风格内容");
            }
        }
    }

    private void validatePositiveInteger(Object value, String fieldName, List<String> errors) {
        if (value == null) {
            return;
        }
        try {
            long id = Long.parseLong(value.toString());
            if (id <= 0) {
                errors.add(fieldName + " 必须是正整数");
            }
        } catch (RuntimeException exception) {
            errors.add(fieldName + " 必须是正整数");
        }
    }

    private void validateParamSize(Integer order, String key, Object value, List<String> errors) {
        if (key == null || key.length() > MAX_PARAM_STRING_LENGTH) {
            errors.add("步骤 " + order + " 参数名长度不能超过 " + MAX_PARAM_STRING_LENGTH);
        }
        if (value instanceof String text && text.length() > MAX_PARAM_STRING_LENGTH) {
            errors.add("步骤 " + order + " 参数 " + key + " 长度不能超过 " + MAX_PARAM_STRING_LENGTH);
        }
        if (value instanceof List<?> list && list.size() > MAX_PARAMS_SIZE) {
            errors.add("步骤 " + order + " 参数 " + key + " 数组长度不能超过 " + MAX_PARAMS_SIZE);
        }
        if (value instanceof Map<?, ?> map && map.size() > MAX_PARAMS_SIZE) {
            errors.add("步骤 " + order + " 参数 " + key + " 对象字段数不能超过 " + MAX_PARAMS_SIZE);
        }
    }

    private boolean looksLikeSql(String value) {
        String lower = value.toLowerCase();
        return lower.contains(";")
                || lower.contains("--")
                || lower.contains("/*")
                || lower.contains("*/")
                || lower.matches(".*\\b(select|insert|update|delete|drop|alter|truncate)\\b.*");
    }

    private Map<String, Object> params(AiPlanStepDTO step) {
        return step.getParams() == null ? Map.of() : step.getParams();
    }

    private boolean isBlankValue(Object value) {
        return stringValue(value) == null;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
