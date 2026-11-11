package com.remotesensing.platform.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.exception.BusinessException;

/** Validates the statistics contract of the task that actually produced the result. */
public final class ResultMetadataValidator {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ResultMetadataValidator() {}

    public static void validate(JsonNode metadata, String taskType, String taskParams) {
        if ("NDVI".equals(taskType)) {
            NdviMetadataValidator.validate(metadata, taskType);
            NdviMetadataValidator.validateTaskRegion(metadata, taskParams);
            return;
        }
        // Older workers complete NDWI and change tasks without trustworthy statistics.
        if (metadata == null || metadata.isNull()) return;
        require("NDWI".equals(taskType) && "NDWI".equals(metadata.path("algorithm").asText())
                && metadata.path("schemaVersion").isIntegralNumber()
                && metadata.path("schemaVersion").canConvertToInt()
                && metadata.path("schemaVersion").intValue() == 1
                && "cropped_raster".equals(metadata.path("scope").asText()) && !metadata.has("regionSnapshot"),
                "算法或统计范围不匹配");
        JsonNode bands = metadata.path("bandMapping");
        JsonNode params = parseParams(taskParams);
        int green = band(bands, params, "greenBand", 2);
        int nir = band(bands, params, "nirBand", 4);
        require(green != nir, "波段必须不同");
        NdviMetadataValidator.validateStatistics(metadata.path("statistics"));
    }

    private static int band(JsonNode bands, JsonNode params, String key, int legacyDefault) {
        JsonNode actual = bands.path(key);
        require(actual.isIntegralNumber() && actual.canConvertToInt() && actual.intValue() > 0
                && actual.intValue() == storedBand(params.path(key), legacyDefault), "波段与任务参数不匹配");
        return actual.intValue();
    }

    private static int storedBand(JsonNode value, int legacyDefault) {
        if (value.isMissingNode()) return legacyDefault;
        if (value.isNumber() && value.canConvertToInt()) return value.intValue();
        if (value.isTextual()) {
            try {
                return Integer.parseInt(value.textValue().strip());
            } catch (NumberFormatException ignored) {
                // Keep the validation error uniform for all invalid stored parameters.
            }
        }
        return 0;
    }

    private static JsonNode parseParams(String taskParams) {
        try {
            return taskParams == null || taskParams.isBlank() ? JSON.createObjectNode() : JSON.readTree(taskParams);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored task params are invalid", e);
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "结果统计元数据无效：" + message);
        }
    }
}
