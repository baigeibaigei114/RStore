package com.remotesensing.platform.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 输入契约检查；来源声明不是科学真实性认证。 */
public final class InputAdmission {
    public static final int MAX_MANIFEST_BYTES = 65536;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private InputAdmission() {}

    public static ObjectNode validate(byte[] bytes, Path file, GeoTiffMetadataVO actual) {
        require(bytes.length > 0 && bytes.length <= MAX_MANIFEST_BYTES, "来源 JSON 必须为 1~65536 字节");
        try {
            JsonNode m = JSON.readTree(bytes);
            require(m != null && m.isObject(), "来源 JSON 必须是对象");
            require(m.path("schemaVersion").isIntegralNumber() && m.path("schemaVersion").canConvertToInt()
                    && m.path("schemaVersion").intValue() == 1,
                    "不支持的输入契约版本");
            equal(m, "productType", "SENTINEL2_L2A");
            equal(m, "preprocessingVersion", "s2-reflectance-v1");
            equal(m, "inputUnits", "surface_reflectance");
            require(m.path("sourceId").isTextual() && !m.path("sourceId").asText().isBlank()
                    && m.path("sourceId").asText().length() <= 256, "缺少有效 sourceId");
            OffsetDateTime.parse(m.path("captureTime").asText());
            String hash = m.path("sha256").asText();
            require(hash.matches("[a-fA-F0-9]{64}") && hash.equalsIgnoreCase(sha256(file)),
                    "TIFF 与来源 JSON 的 SHA256 不一致");
            require(actual.getWidth() != null && actual.getHeight() != null
                    && actual.getWidth() > 0 && actual.getHeight() > 0
                    && actual.getWidth() <= 2048 && actual.getHeight() <= 2048, "输入尺寸必须在 1~2048 范围内");
            require(m.path("width").isIntegralNumber() && m.path("height").isIntegralNumber()
                    && m.path("width").canConvertToInt() && m.path("height").canConvertToInt()
                    && m.path("width").asInt() == actual.getWidth()
                    && m.path("height").asInt() == actual.getHeight(), "声明尺寸与栅格不一致");
            require(actual.getCrs() != null && actual.getCrs().equals(m.path("crs").asText()), "坐标系缺失或不一致");
            require(Objects.equals(actual.getBandCount(), 4)
                    && List.of("B02", "B03", "B04", "B08").equals(actual.getBandDescriptions())
                    && JSON.valueToTree(actual.getBandDescriptions()).equals(m.path("bandOrder")), "波段必须为 B02/B03/B04/B08");
            require(List.of("float32", "float32", "float32", "float32").equals(actual.getDtypes()), "栅格必须为 Float32");
            require("surface_reflectance".equals(actual.getInputUnits()), "栅格缺少反射率单位标签");
            require(actual.getNodata() != null && actual.getNodata().doubleValue() == -9999, "输入 NoData 必须为 -9999");
            require(actual.getScales() != null && actual.getScales().size() == 4
                    && actual.getScales().stream().allMatch(v -> v != null && v.doubleValue() == 1)
                    && actual.getOffsets() != null && actual.getOffsets().size() == 4
                    && actual.getOffsets().stream().allMatch(v -> v != null && v.doubleValue() == 0),
                    "反射率栅格不能再次声明缩放或偏移");
            require(actual.getTransform() != null && actual.getTransform().size() == 6 && m.path("transform").isArray()
                    && m.path("transform").size() == 6, "仿射变换必须包含 6 个参数");
            for (int i = 0; i < 6; i++) {
                double declared = number(m.path("transform").path(i));
                double observed = actual.getTransform().get(i).doubleValue();
                require(Double.isFinite(observed) && Math.abs(declared - observed) <= 1e-9, "仿射变换与栅格不一致");
            }
            var t = actual.getTransform();
            require(Math.abs(t.get(0).doubleValue() * t.get(4).doubleValue()
                    - t.get(1).doubleValue() * t.get(3).doubleValue()) > 0, "仿射变换退化");
            equal(m, "sclResampling", "nearest");
            require(JSON.valueToTree(List.of(4, 5, 6, 7)).equals(m.path("sclValidClasses")), "不支持的质量掩膜规则");
            for (String band : List.of("blue", "green", "red", "nir")) {
                require(number(m.path("calibration").path(band).path("scale")) > 0, "scale 必须大于零");
                number(m.path("calibration").path(band).path("offset"));
            }
            JsonNode window = m.path("window");
            require(window.isArray() && window.size() == 4, "缺少裁剪窗口");
            for (JsonNode value : window) require(value.isIntegralNumber() && value.canConvertToInt()
                    && value.intValue() >= 0, "裁剪窗口参数必须是非负整数");
            require(window.get(2).intValue() == actual.getWidth() && window.get(3).intValue() == actual.getHeight(),
                    "裁剪窗口与输出尺寸不一致");
            ObjectNode admission = JSON.createObjectNode();
            admission.put("status", "PASSED");
            admission.put("validatorVersion", "input-v1");
            admission.put("checkedAt", OffsetDateTime.now().toString());
            admission.set("manifest", m);
            return admission;
        } catch (IOException | java.time.format.DateTimeParseException e) {
            throw invalid("来源 JSON 无法解析或采集时间缺少有效时区");
        }
    }

    public static ObjectNode snapshot(String metadata, Long imageId, Map<String, Object> params) {
        try {
            JsonNode admission = JSON.readTree(metadata == null || metadata.isBlank() ? "{}" : metadata).path("admission");
            require("PASSED".equals(admission.path("status").asText())
                    && "input-v1".equals(admission.path("validatorVersion").asText()), "影像未通过标准化输入校验，请上传 TIFF 与来源 JSON");
            require(isBand(params.get("redBand"), 3) && isBand(params.get("nirBand"), 4),
                    "NDVI 波段必须与已校验的 red=3、nir=4 一致");
            ObjectNode result = admission.deepCopy();
            result.put("imageId", imageId);
            result.put("algorithmVersion", "ndvi-v1");
            result.put("denominatorTolerance", 1e-6);
            return result;
        } catch (IOException e) {
            throw invalid("影像元数据无法解析");
        }
    }

    private static boolean isBand(Object value, int expected) {
        return value instanceof Number n && n.doubleValue() == expected;
    }

    public static String sha256(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double number(JsonNode value) {
        require(value.isNumber() && Double.isFinite(value.doubleValue()), "校准或空间参数必须为有限数值");
        return value.doubleValue();
    }

    private static void equal(JsonNode node, String field, String expected) {
        require(expected.equals(node.path(field).asText()), "不支持的 " + field);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ResultCode.PARAM_ERROR.getCode(), message);
    }
}
