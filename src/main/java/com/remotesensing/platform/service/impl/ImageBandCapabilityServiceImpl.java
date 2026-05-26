package com.remotesensing.platform.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.dto.RemoteSensingTaskMessage.TaskType;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.service.ImageBandCapabilityService;
import com.remotesensing.platform.vo.GeoTiffMetadataVO;
import com.remotesensing.platform.vo.ImageBandCapabilityVO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ImageBandCapabilityServiceImpl implements ImageBandCapabilityService {

    private static final String UNKNOWN = "UNKNOWN";
    private static final String LOW = "LOW";
    private static final Set<String> TRUSTED_CONFIDENCE = Set.of("HIGH", "MEDIUM");

    private final ObjectMapper objectMapper;

    public ImageBandCapabilityServiceImpl(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 上传阶段补充影像波段能力字段，并保留在 metadataJson 中供后续查询和任务提交复用。
     */
    @Override
    public GeoTiffMetadataVO enrichMetadata(GeoTiffMetadataVO metadata) {
        ImageBandCapabilityVO capability = resolve(metadata);
        metadata.setBandMapping(capability.bandMapping());
        metadata.setBandMappingSource(capability.source());
        metadata.setBandMappingConfidence(capability.confidence());
        metadata.setSupportedTaskTypes(capability.supportedTaskTypes());
        return metadata;
    }

    /**
     * 从影像记录解析波段能力；当 metadataJson 缺失时，退化使用实体上的 bandCount 做基础校验上下文。
     */
    @Override
    public ImageBandCapabilityVO resolve(RsImage image) {
        if (image == null) {
            return unknown();
        }
        GeoTiffMetadataVO metadata = parseMetadata(image.getMetadataJson());
        if (metadata == null) {
            metadata = new GeoTiffMetadataVO();
            metadata.setBandCount(image.getBandCount());
        }
        if (metadata.getBandCount() == null) {
            metadata.setBandCount(image.getBandCount());
        }
        return resolve(metadata);
    }

    /**
     * 在任务提交前校验 NDVI/NDWI 的必要波段，并将可信映射写回任务参数。
     */
    @Override
    public void validateAndFillTaskParams(RsImage image, TaskType taskType, Map<String, Object> params) {
        if (taskType == TaskType.CHANGE_DETECTION) {
            return;
        }
        ImageBandCapabilityVO capability = resolve(image);
        if (!capability.supportedTaskTypes().contains(taskType.name())) {
            throw new BusinessException(
                    ResultCode.PARAM_ERROR.getCode(),
                    "当前影像缺少可信波段映射，不能执行 " + taskType.name() + " 任务"
            );
        }
        if (taskType == TaskType.NDVI) {
            requireMappedParam(params, capability, "red", "redBand");
            requireMappedParam(params, capability, "nir", "nirBand");
        } else if (taskType == TaskType.NDWI) {
            requireMappedParam(params, capability, "green", "greenBand");
            requireMappedParam(params, capability, "nir", "nirBand");
        }
    }

    /**
     * 按优先级解析波段能力：显式 metadata 映射优先，其次 GeoTIFF 波段描述，最后颜色解释。
     */
    private ImageBandCapabilityVO resolve(GeoTiffMetadataVO metadata) {
        if (metadata == null) {
            return unknown();
        }
        if (metadata.getBandMapping() != null && !metadata.getBandMapping().isEmpty()) {
            Map<String, Integer> normalized = sanitizeMapping(
                    normalizeMapping(metadata.getBandMapping()),
                    metadata.getBandCount()
            );
            String confidence = defaultIfBlank(metadata.getBandMappingConfidence(), "HIGH").toUpperCase(Locale.ROOT);
            String source = defaultIfBlank(metadata.getBandMappingSource(), "METADATA");
            return new ImageBandCapabilityVO(normalized, source, confidence, supportedTasks(normalized, confidence));
        }

        Map<String, Integer> fromDescriptions = inferFromDescriptions(metadata.getBandDescriptions());
        if (isTrustedMultispectral(fromDescriptions)) {
            return new ImageBandCapabilityVO(
                    fromDescriptions,
                    "GEOTIFF_BAND_DESCRIPTION",
                    "HIGH",
                    supportedTasks(fromDescriptions, "HIGH")
            );
        }

        Map<String, Integer> fromColors = inferFromColorInterpretations(metadata.getColorInterpretations());
        if (!fromColors.isEmpty()) {
            return new ImageBandCapabilityVO(
                    fromColors,
                    "GEOTIFF_COLOR_INTERPRETATION",
                    "MEDIUM",
                    supportedTasks(fromColors, "MEDIUM")
            );
        }

        return unknown();
    }

    /**
     * 将数据库中的 metadataJson 反序列化为结构化元数据，解析失败时视为无可用波段能力。
     */
    private GeoTiffMetadataVO parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(metadataJson, GeoTiffMetadataVO.class);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /**
     * 从 GeoTIFF band description 中识别 Sentinel-2 常见 B02/B03/B04/B08 波段角色。
     */
    private Map<String, Integer> inferFromDescriptions(List<String> descriptions) {
        if (descriptions == null || descriptions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> mapping = new LinkedHashMap<>();
        for (int i = 0; i < descriptions.size(); i++) {
            String role = detectRole(descriptions.get(i));
            if (role != null && !mapping.containsKey(role)) {
                mapping.put(role, i + 1);
            }
        }
        return mapping;
    }

    /**
     * 从 GeoTIFF color interpretation 中识别 RGB 可见光波段。
     */
    private Map<String, Integer> inferFromColorInterpretations(List<String> colorInterpretations) {
        if (colorInterpretations == null || colorInterpretations.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> mapping = new LinkedHashMap<>();
        for (int i = 0; i < colorInterpretations.size(); i++) {
            String role = detectRole(colorInterpretations.get(i));
            if (role != null && List.of("red", "green", "blue").contains(role) && !mapping.containsKey(role)) {
                mapping.put(role, i + 1);
            }
        }
        return mapping;
    }

    /**
     * 将波段描述文本归一化后映射为 red、green、blue、nir 等业务角色。
     */
    private String detectRole(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (text.contains("b02") || text.contains("blue")) {
            return "blue";
        }
        if (text.contains("b03") || text.contains("green")) {
            return "green";
        }
        if (text.contains("b04") || text.equals("red") || text.contains("redband")) {
            return "red";
        }
        if (text.contains("b08") || text.contains("nir") || text.contains("nearinfrared")) {
            return "nir";
        }
        return null;
    }

    /**
     * 判断描述推断出的映射是否覆盖多光谱分析所需的基础可见光和近红外波段。
     */
    private boolean isTrustedMultispectral(Map<String, Integer> mapping) {
        return mapping.containsKey("red") && mapping.containsKey("green") && mapping.containsKey("blue")
                && mapping.containsKey("nir");
    }

    /**
     * 统一波段角色命名，避免 metadataJson 中大小写或空格差异影响后续判断。
     */
    private Map<String, Integer> normalizeMapping(Map<String, Integer> mapping) {
        Map<String, Integer> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : mapping.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            normalized.put(entry.getKey().trim().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return normalized;
    }

    /**
     * 清洗元数据中的波段映射，只保留合法角色和不超过影像波段数的正整数编号。
     */
    private Map<String, Integer> sanitizeMapping(Map<String, Integer> mapping, Integer bandCount) {
        Map<String, Integer> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : mapping.entrySet()) {
            String role = entry.getKey();
            Integer band = entry.getValue();
            if (!List.of("red", "green", "blue", "nir").contains(role)) {
                continue;
            }
            if (band == null || band < 1) {
                continue;
            }
            if (bandCount != null && band > bandCount) {
                continue;
            }
            sanitized.put(role, band);
        }
        return sanitized;
    }

    /**
     * 根据可信映射推导当前影像可以直接提交的处理任务类型。
     */
    private List<String> supportedTasks(Map<String, Integer> mapping, String confidence) {
        if (!TRUSTED_CONFIDENCE.contains(confidence)) {
            return List.of();
        }
        Set<String> taskTypes = new LinkedHashSet<>();
        if (mapping.containsKey("red") && mapping.containsKey("nir")) {
            taskTypes.add(TaskType.NDVI.name());
        }
        if (mapping.containsKey("green") && mapping.containsKey("nir")) {
            taskTypes.add(TaskType.NDWI.name());
        }
        return new ArrayList<>(taskTypes);
    }

    /**
     * 校验任务参数中的波段编号与可信映射一致；未传时自动填入可信值。
     */
    private void requireMappedParam(Map<String, Object> params,
                                    ImageBandCapabilityVO capability,
                                    String role,
                                    String paramName) {
        Integer mappedBand = capability.bandMapping().get(role);
        if (mappedBand == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "影像缺少 " + role + " 波段映射");
        }
        validateMappedBandRange(mappedBand);
        Object existing = params.get(paramName);
        if (existing == null) {
            params.put(paramName, mappedBand);
            return;
        }
        Integer requestedBand = toInteger(existing);
        validateMappedBandRange(requestedBand);
        if (!mappedBand.equals(requestedBand)) {
            throw new BusinessException(
                    ResultCode.PARAM_ERROR.getCode(),
                    paramName + " 与可信波段映射不一致，期望值为 " + mappedBand
            );
        }
    }

    /**
     * 将请求参数中的波段编号转换为整数，供一致性校验使用。
     */
    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException exception) {
                throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "波段编号必须是整数");
            }
        }
        throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "波段编号不能为空");
    }

    /**
     * 提交任务前再次校验最终写入 params 的波段编号，避免非法映射穿透到 Worker。
     */
    private void validateMappedBandRange(Integer band) {
        if (band == null || band < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "波段编号必须大于 0");
        }
    }

    /**
     * 处理可选字符串配置，避免来源或置信度字段为空时传播 null。
     */
    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    /**
     * 构造未知波段能力结果，表示当前影像不能直接提交指数计算任务。
     */
    private ImageBandCapabilityVO unknown() {
        return new ImageBandCapabilityVO(Collections.emptyMap(), UNKNOWN, LOW, List.of());
    }
}
