package com.remotesensing.platform.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.agent.AgentJson;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.RsImageMapper;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BuildingChange {
    public static final String TYPE = "BUILDING_CHANGE_DETECTION";
    public static final String SNAPSHOT = "buildingChangeSnapshot";
    public static final String WEIGHTS = "743645b87199c96bd0c3271ae5df6f0918b3f1d84618dfb9396843ca08ee4a3e";
    private static final Set<String> METRIC_UNITS = Set.of("metre", "meter");

    private BuildingChange() {}

    public static RsImage prepare(RsImageMapper images, String owner, long firstId, long secondId, Map<String, Object> params) {
        require(firstId != secondId, "请选择两期不同的影像");
        var low = images.lockAccessibleById(Math.min(firstId, secondId), owner);
        var high = images.lockAccessibleById(Math.max(firstId, secondId), owner);
        require(usable(low) && usable(high) && "READY".equals(low.getStatus()) && "READY".equals(high.getStatus()),
                "两期影像必须可访问且处于 READY 状态，请重新选择");
        require(low.getAcquisitionTime() != null && high.getAcquisitionTime() != null
                && !low.getAcquisitionTime().isEqual(high.getAcquisitionTime()), "两期影像须记录不同的采集时间");
        boolean lowFirst = low.getAcquisitionTime().isBefore(high.getAcquisitionTime());
        RsImage before = lowFirst ? low : high;
        RsImage after = lowFirst ? high : low;
        ObjectNode snapshot = snapshot(before, after);
        params.clear();
        params.put("beforeImageId", before.getId());
        params.put("afterImageId", after.getId());
        params.put("beforeBucket", before.getMinioBucket());
        params.put("beforeObjectKey", before.getObjectKey());
        params.put("afterBucket", after.getMinioBucket());
        params.put("afterObjectKey", after.getObjectKey());
        params.put(SNAPSHOT, snapshot);
        return after;
    }

    public static void validateClaim(RsImageMapper images, String owner, long primaryId, String rawParams) {
        JsonNode saved = AgentJson.parse(rawParams).path(SNAPSHOT);
        long beforeId = saved.path("before").path("imageId").asLong();
        long afterId = saved.path("after").path("imageId").asLong();
        require(beforeId > 0 && afterId == primaryId && beforeId != afterId, "检测任务的两期影像快照有误");
        var low = images.lockAccessibleById(Math.min(beforeId, afterId), owner);
        var high = images.lockAccessibleById(Math.max(beforeId, afterId), owner);
        RsImage before = beforeId < afterId ? low : high;
        RsImage after = beforeId < afterId ? high : low;
        require(usable(before) && usable(after), "前期或后期影像已失效，请换景后重新提交");
        require(AgentJson.hash(saved).equals(AgentJson.hash(snapshot(before, after))),
                "前期或后期影像已变化，请核对素材后重新提交");
    }

    private static boolean usable(RsImage image) {
        return image != null && image.getDeletedAt() == null
                && ("READY".equals(image.getStatus()) || "PROCESSING".equals(image.getStatus()));
    }

    private static ObjectNode snapshot(RsImage before, RsImage after) {
        require(before.getAcquisitionTime() != null && after.getAcquisitionTime() != null
                && before.getAcquisitionTime().isBefore(after.getAcquisitionTime()), "前后期采集时间有误");
        ObjectNode grid = grid(after);
        require(AgentJson.hash(grid).equals(AgentJson.hash(grid(before))), "两期影像的投影、尺寸和仿射网格必须完全一致");
        ObjectNode result = AgentJson.object();
        result.set("grid", grid);
        result.set("before", source(before));
        result.set("after", source(after));
        result.set("model", AgentJson.object().put("name", "TinyCD LEVIR-CD").put("weightsSha256", WEIGHTS)
                .put("tileSize", 256).put("coreSize", 192).put("threshold", 0.5).put("invalidRgbFill", 0)
                .put("normalization", "imagenet").put("padding", "reflect"));
        return result;
    }

    private static ObjectNode source(RsImage image) {
        var identity = AgentJson.object().put("id", image.getId())
                .put("bucket", image.getMinioBucket()).put("key", image.getObjectKey());
        return AgentJson.object().put("imageId", image.getId()).put("name", image.getImageName())
                .put("captureTime", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(image.getAcquisitionTime()))
                .put("assetIdentity", AgentJson.hash(identity));
    }

    private static ObjectNode grid(RsImage image) {
        JsonNode meta = AgentJson.parse(image.getMetadataJson());
        require("GTiff".equalsIgnoreCase(image.getFileFormat()) || "GEOTIFF".equalsIgnoreCase(image.getFileFormat())
                || "TIFF".equalsIgnoreCase(image.getFileFormat()), "模型检测需要 GeoTIFF");
        require(meta.path("dtypes").equals(AgentJson.JSON.valueToTree(List.of("uint8", "uint8", "uint8")))
                && meta.path("colorInterpretations").equals(AgentJson.JSON.valueToTree(List.of("red", "green", "blue"))),
                "模型检测需要三波段 uint8 RGB 影像");
        int width = meta.path("width").asInt(), height = meta.path("height").asInt();
        require(width > 0 && height > 0 && width <= 2400 && height <= 2400, "两期影像单边须在 1 至 2400 像素内");
        String crs = meta.path("crs").asText();
        require(!crs.isBlank() && METRIC_UNITS.contains(meta.path("resolutionUnit").asText()), "模型检测需要米制投影");
        JsonNode t = meta.path("transform");
        require(t.isArray() && t.size() == 6, "影像缺少仿射网格");
        for (JsonNode value : t) require(value.isNumber() && Double.isFinite(value.doubleValue()), "影像仿射参数无效");
        require(pixelArea(t) > 0 && Double.isFinite(pixelArea(t)), "影像仿射网格退化");
        return AgentJson.object().put("crs", crs).put("width", width).put("height", height).set("transform", t);
    }

    public static double pixelArea(JsonNode t) {
        return Math.abs(t.get(0).doubleValue() * t.get(4).doubleValue() - t.get(1).doubleValue() * t.get(3).doubleValue());
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), message);
    }
}
