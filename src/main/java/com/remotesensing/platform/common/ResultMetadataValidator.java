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
        if (BuildingChange.TYPE.equals(taskType)) {
            validateBuildingChange(metadata, parseParams(taskParams));
            return;
        }
        // Older workers complete NDWI and change tasks without trustworthy statistics.
        if (metadata == null || metadata.isNull()) return;
        require(("NDWI".equals(taskType) || "CHANGE_DETECTION".equals(taskType))
                && taskType.equals(metadata.path("algorithm").asText())
                && metadata.path("schemaVersion").isIntegralNumber()
                && metadata.path("schemaVersion").canConvertToInt()
                && metadata.path("schemaVersion").intValue() == 1
                && "cropped_raster".equals(metadata.path("scope").asText()) && !metadata.has("regionSnapshot"),
                "算法或统计范围不匹配");
        JsonNode params = parseParams(taskParams);
        JsonNode stats = metadata.path("statistics");
        NdviMetadataValidator.validateStatistics(stats);
        if ("CHANGE_DETECTION".equals(taskType)) {
            validateChange(metadata, params, stats);
            return;
        }
        JsonNode bands = metadata.path("bandMapping");
        int green = band(bands, params, "greenBand", 2);
        int nir = band(bands, params, "nirBand", 4);
        require(green != nir, "波段必须不同");
    }

    private static void validateBuildingChange(JsonNode metadata, JsonNode params) {
        require(metadata != null && BuildingChange.TYPE.equals(metadata.path("algorithm").asText())
                && integer(metadata, "schemaVersion") == 1 && "aligned_rgb_raster".equals(metadata.path("scope").asText()),
                "建筑物变化检测结果缺少有效统计");
        JsonNode expected = params.path(BuildingChange.SNAPSHOT);
        require(expected.isObject() && com.remotesensing.platform.agent.AgentJson.hash(expected)
                .equals(com.remotesensing.platform.agent.AgentJson.hash(metadata.path("snapshot"))), "建筑物变化检测的素材或模型快照不匹配");
        JsonNode grid = expected.path("grid");
        JsonNode stats = metadata.path("statistics");
        long total = integer(stats, "totalPixelCount");
        long valid = integer(stats, "validPixelCount");
        long changed = integer(stats, "changedPixelCount");
        require(total == integer(grid, "width") * integer(grid, "height")
                && changed >= 0 && changed <= valid && valid > 0 && valid <= total, "建筑物变化检测像素统计不一致");
        double area = BuildingChange.pixelArea(grid.path("transform"));
        require(area > 0 && number(stats, "pixelAreaM2") == area
                && number(stats, "changedAreaM2") == changed * area
                && Math.abs(number(stats, "changeRatio") - (double) changed / valid) < 1e-9
                && Math.abs(number(stats, "validPixelRatio") - (double) valid / total) < 1e-9, "建筑物变化检测面积或比例不一致");
    }

    private static void validateChange(JsonNode metadata, JsonNode params, JsonNode stats) {
        JsonNode comparison = metadata.path("comparison");
        long before = integer(comparison, "beforeImageId");
        long after = integer(comparison, "afterImageId");
        require(before > 0 && after > 0 && before != after
                && before == integer(params, "beforeImageId") && after == integer(params, "afterImageId"),
                "两期来源与任务不匹配");
        band(comparison, params, "band", 1);
        JsonNode savedThreshold = params.path("threshold");
        double expected = savedThreshold.isMissingNode() ? 0.2 : savedThreshold.asDouble(Double.NaN);
        double threshold = number(comparison, "threshold");
        require(threshold >= 0 && threshold == expected, "变化阈值与任务不匹配");

        JsonNode grid = metadata.path("grid");
        long width = integer(grid, "width"), height = integer(grid, "height");
        require("same_grid".equals(grid.path("alignment").asText()) && width > 0 && height > 0
                && width <= Integer.MAX_VALUE && height <= Integer.MAX_VALUE
                && width * height == stats.path("totalPixelCount").longValue(), "对齐范围与像素总数不匹配");
        JsonNode sourceGrid = params.path("changeSourceSnapshot");
        require((!sourceGrid.has("width") || width == integer(sourceGrid, "width"))
                && (!sourceGrid.has("height") || height == integer(sourceGrid, "height")), "结果网格与任务快照不匹配");

        long valid = stats.path("validPixelCount").longValue();
        long changed = integer(stats, "changedPixelCount"), unchanged = integer(stats, "unchangedPixelCount");
        double ratio = number(stats, "changeRatio");
        require(changed >= 0 && changed <= valid && unchanged == valid - changed
                && ratio >= 0 && ratio <= 1 && Math.abs(ratio - (double) changed / valid) < 1e-9
                && Math.abs(ratio - stats.path("mean").doubleValue()) < 1e-9
                && stats.path("min").doubleValue() == (unchanged == 0 ? 1 : 0)
                && stats.path("max").doubleValue() == (changed == 0 ? 0 : 1), "变化像素、有效分母或比例不一致");
    }

    private static long integer(JsonNode object, String key) {
        JsonNode value = object.path(key);
        require(value.isIntegralNumber() && value.canConvertToLong(), key + " 必须是整数");
        return value.longValue();
    }

    private static double number(JsonNode object, String key) {
        JsonNode value = object.path(key);
        require(value.isNumber() && Double.isFinite(value.doubleValue()), key + " 必须是有限数值");
        return value.doubleValue();
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
