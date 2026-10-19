package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.remotesensing.platform.common.InputAdmission;
import com.remotesensing.platform.dto.RsImageSearchDTO;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.mapper.*;
import com.remotesensing.platform.vo.MonitoringRegionVO;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.remotesensing.platform.agent.AgentJson.*;

/** Reuses owner-filtered business lookups; only public candidate summaries cross to Python. */
@Component
public class AgentResources {
    private final RsImageMapper images;
    private final MonitoringRegionMapper regions;
    public AgentResources(RsImageMapper images, MonitoringRegionMapper regions) { this.images = images; this.regions = regions; }
    public RsImage image(long id, String owner, boolean lock) {
        RsImage image = lock ? images.lockAccessibleById(id, owner) : images.selectAccessibleById(id, owner);
        if (image == null || image.getDeletedAt() != null || !(owner.equals(image.getOwnerId()) || "PUBLIC".equals(image.getVisibility())))
            throw AgentFailure.conflict("RESOURCE_UNAVAILABLE");
        if (!"READY".equals(image.getStatus())) throw AgentFailure.conflict("RESOURCE_NOT_READY");
        InputAdmission.snapshot(image.getMetadataJson(), image.getId(), Map.of("redBand", 3, "nirBand", 4));
        return image;
    }
    public MonitoringRegionVO region(long id, String owner, boolean lock) {
        MonitoringRegionVO region = lock ? regions.lock(id, owner) : regions.find(id, owner);
        if (region == null) throw AgentFailure.conflict("RESOURCE_UNAVAILABLE");
        return region;
    }
    public ObjectNode imageSummary(RsImage image) {
        sensitive(image.getImageName()); sensitive(image.getSensorType());
        ObjectNode summary = object().put("id", image.getId().toString()).put("name", image.getImageName()).put("admission", "PASSED");
        summary.put("sensor", image.getSensorType());
        summary.put("captureTime", captureTime(image));
        require(image.getImageName() != null && image.getImageName().length() <= 255
                && (image.getSensorType() == null || image.getSensorType().length() <= 50));
        return summary;
    }
    public ObjectNode regionSummary(MonitoringRegionVO region) {
        sensitive(region.getName());
        return object().put("id", region.getId().toString()).put("name", region.getName()).put("version", region.getVersion());
    }
    public ObjectNode tool(String tool, JsonNode args, String owner, ObjectNode context) {
        ObjectNode next = context.deepCopy();
        boolean more = false;
        if (tool.equals("searchImages")) {
            RsImageSearchDTO query = new RsImageSearchDTO();
            query.setKeyword(optionalText(args, "keyword", 100)); query.setSensor(optionalText(args, "sensor", 50));
            String start = optionalText(args, "startTime", 50), end = optionalText(args, "endTime", 50);
            if (start != null) query.setStartTime(OffsetDateTime.parse(start));
            if (end != null) query.setEndTime(OffsetDateTime.parse(end));
            if (args.path("maxCloudPercent").isNumber()) query.setMaxCloudPercent(args.path("maxCloudPercent").decimalValue());
            List<RsImage> scanned = images.searchPage(query, 0, 100, owner);
            ArrayNode candidates = array();
            for (RsImage scannedImage : scanned) {
                try {
                    RsImage current = image(scannedImage.getId(), owner, false);
                    imageFingerprint(current); // A PASSED marker alone is not a complete draft resource.
                    if (candidates.size() == 10) { more = true; break; }
                    candidates.add(imageSummary(current));
                } catch (com.remotesensing.platform.exception.BusinessException ignored) {
                    // Ordinary or no-longer-accessible assets are deliberately excluded from NDVI candidates.
                }
            }
            more |= scanned.size() == 100;
            next.set("imageCandidates", candidates);
            if (!contains(candidates, next.path("selectedImageId").asText())) next.remove("selectedImageId");
        } else {
            ArrayNode candidates = array();
            List<MonitoringRegionVO> scanned = regions.list(owner);
            String keyword = optionalText(args, "keyword", 100);
            for (MonitoringRegionVO region : scanned) {
                if (keyword != null && !region.getName().contains(keyword)) continue;
                if (candidates.size() == 10) { more = true; break; }
                candidates.add(regionSummary(region));
            }
            more |= scanned.size() == 100;
            next.set("regionCandidates", candidates);
            if (!contains(candidates, next.path("selectedRegionId").asText())) next.remove("selectedRegionId");
        }
        // Kept locally; hasMore is not a field of Python SelectionContext.
        next.put("hasMoreCandidates", context.path("hasMoreCandidates").asBoolean() || more);
        return next;
    }
    public void select(ObjectNode context, JsonNode body, String owner) {
        if (body.hasNonNull("selectedImageId")) {
            long id = id(body, "selectedImageId");
            context.set("imageCandidates", array().add(imageSummary(image(id, owner, false))));
            context.put("selectedImageId", Long.toString(id));
        }
        if (body.hasNonNull("selectedRegionId")) {
            long id = id(body, "selectedRegionId");
            context.set("regionCandidates", array().add(regionSummary(region(id, owner, false))));
            context.put("selectedRegionId", Long.toString(id));
        }
    }
    public ObjectNode pythonContext(JsonNode context) {
        ObjectNode safe = object();
        safe.set("imageCandidates", context.path("imageCandidates").isArray() ? context.path("imageCandidates").deepCopy() : array());
        safe.set("regionCandidates", context.path("regionCandidates").isArray() ? context.path("regionCandidates").deepCopy() : array());
        if (context.hasNonNull("selectedImageId")) safe.put("selectedImageId", context.path("selectedImageId").asText());
        if (context.hasNonNull("selectedRegionId")) safe.put("selectedRegionId", context.path("selectedRegionId").asText());
        return safe;
    }
    public static boolean contains(JsonNode candidates, String id) {
        for (JsonNode value : candidates) if (value.path("id").asText().equals(id)) return true;
        return false;
    }
    public ObjectNode imageFingerprint(RsImage image) {
        JsonNode metadata = parse(image.getMetadataJson());
        JsonNode admission = metadata.path("admission");
        String input = hashField(admission.path("manifest"), "sha256");
        ObjectNode fingerprint = object().put("id", image.getId().toString()).put("inputSHA256", input)
                .put("admissionSha256", hash(admission)).put("updatedAt", image.getUpdatedAt() == null ? null : image.getUpdatedAt().toString())
                .put("assetLocationSha256", sha(image.getMinioBucket() + "\n" + image.getObjectKey()));
        fingerprint.set("bandMapping", object().put("redBand", 3).put("nirBand", 4));
        return fingerprint;
    }
    public ObjectNode regionSnapshot(MonitoringRegionVO region) {
        ObjectNode snapshot = object().put("schemaVersion", 1).put("regionId", region.getId()).put("name", region.getName())
                .put("version", region.getVersion()).put("crs", "EPSG:4326").put("pixelRule", "center_covered");
        snapshot.set("geometry", parse(region.getGeometryJson()));
        return snapshot;
    }
    public ObjectNode draft(AgentExecution execution, JsonNode action, JsonNode context) {
        String imageId = text(action, "imageId", 19), regionId = text(action, "monitoringRegionId", 19);
        if (!contains(context.path("imageCandidates"), imageId) || !contains(context.path("regionCandidates"), regionId)
                || (context.path("imageCandidates").size() > 1 && !context.hasNonNull("selectedImageId"))
                || (context.path("regionCandidates").size() > 1 && !context.hasNonNull("selectedRegionId"))
                || (context.hasNonNull("selectedImageId") && !context.path("selectedImageId").asText().equals(imageId))
                || (context.hasNonNull("selectedRegionId") && !context.path("selectedRegionId").asText().equals(regionId))) throw AgentProtocol.invalid();
        RsImage image = image(id(action, "imageId"), execution.getOwnerId(), false);
        MonitoringRegionVO region = region(id(action, "monitoringRegionId"), execution.getOwnerId(), false);
        ObjectNode draft = object().put("schemaVersion", 1).put("executionId", execution.getId()).put("draftVersion", execution.getDraftVersion())
                .put("imageId", imageId).put("imageName", image.getImageName()).put("captureTime", captureTime(image))
                .put("monitoringRegionId", regionId).put("regionName", region.getName()).put("regionVersion", region.getVersion())
                .put("regionGeometrySha256", hash(parse(region.getGeometryJson()))).put("taskType", "NDVI")
                .put("operationLabel", "提交一次单期区域 NDVI 计算");
        draft.set("imageFingerprint", imageFingerprint(image));
        draft.set("params", object().put("redBand", 3).put("nirBand", 4));
        draft.set("regionSnapshot", regionSnapshot(region));
        draft.set("submitIntent", object().put("imageId", image.getId()).put("monitoringRegionId", region.getId()).put("taskType", "NDVI")
                .set("params", object().put("redBand", 3).put("nirBand", 4)));
        return draft;
    }
    public String draftHash(JsonNode draft) {
        ObjectNode value = object();
        for (String field : List.of("schemaVersion", "executionId", "draftVersion", "imageFingerprint", "monitoringRegionId",
                "regionVersion", "regionGeometrySha256", "taskType", "params")) value.set(field, draft.path(field));
        return hash(value);
    }
    private String captureTime(RsImage image) {
        // OffsetDateTime.toString omits zero seconds; Python's exact wire schema requires them.
        return image.getAcquisitionTime() == null ? null
                : java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(image.getAcquisitionTime());
    }
}
