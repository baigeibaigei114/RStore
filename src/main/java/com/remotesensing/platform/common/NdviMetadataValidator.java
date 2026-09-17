package com.remotesensing.platform.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.remotesensing.platform.exception.BusinessException;

/** Worker 统计是不可信输入，在更新任务状态前校验。旧任务可缺少统计。 */
public final class NdviMetadataValidator {
    private NdviMetadataValidator() {}

    public static void validate(JsonNode metadata, String taskType) {
        if (metadata == null || metadata.isNull()) {
            return;
        }
        require("NDVI".equals(taskType), "统计契约仅适用于 NDVI");
        require(metadata.isObject(), "统计必须是对象");
        require(integer(metadata, "schemaVersion") == 1, "不支持的统计版本");
        require("NDVI".equals(metadata.path("algorithm").asText()), "算法不一致");
        require("cropped_raster".equals(metadata.path("scope").asText()), "统计范围无效");
        JsonNode bands = metadata.path("bandMapping");
        long red = integer(bands, "redBand");
        long nir = integer(bands, "nirBand");
        require(red > 0 && nir > 0 && red <= Integer.MAX_VALUE && nir <= Integer.MAX_VALUE
                && red != nir, "波段映射无效");
        JsonNode stats = metadata.path("statistics");
        long total = integer(stats, "totalPixelCount");
        long valid = integer(stats, "validPixelCount");
        long invalid = integer(stats, "invalidPixelCount");
        require(total > 0 && valid > 0 && valid <= total && invalid == total - valid,
                "像素数量不一致");
        double ratio = number(stats, "validPixelRatio");
        require(ratio > 0 && ratio <= 1 && Math.abs(ratio - (double) valid / total) < 1e-9,
                "有效像素比例不一致");
        double min = number(stats, "min");
        double max = number(stats, "max");
        double mean = number(stats, "mean");
        require(min <= mean && mean <= max, "统计数值顺序不一致");
    }

    private static long integer(JsonNode object, String name) {
        JsonNode value = object.path(name);
        require(value.isIntegralNumber() && value.canConvertToLong(), name + " 必须为整数");
        return value.longValue();
    }

    private static double number(JsonNode object, String name) {
        JsonNode value = object.path(name);
        require(value.isNumber() && Double.isFinite(value.doubleValue()), name + " 必须为有限数值");
        return value.doubleValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "结果统计元数据无效：" + message);
        }
    }
}
