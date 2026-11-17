package com.remotesensing.platform.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.common.InputAdmission;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.MonitoringRegionMapper;
import com.remotesensing.platform.mapper.RsImageMapper;
import com.remotesensing.platform.vo.MonitoringRegionVO;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import static com.remotesensing.platform.agent.AgentJson.*;

@Service
public class AnalysisPreparation {
    private static final ZoneId CAPTURE_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_CHOICES = 10;
    private static final int IMAGE_SCAN_LIMIT = 101;
    private static final Map<String, Object> NDVI_BANDS = Map.of("redBand", 3, "nirBand", 4);

    private final AgentClient client;
    private final AgentProperties limits;
    private final RsImageMapper images;
    private final MonitoringRegionMapper regions;
    private final AgentResources resources;

    public AnalysisPreparation(AgentClient client, AgentProperties limits, RsImageMapper images,
                               MonitoringRegionMapper regions, AgentResources resources) {
        this.client = client;
        this.limits = limits;
        this.images = images;
        this.regions = regions;
        this.resources = resources;
    }

    public ObjectNode prepare(JsonNode body, String owner) {
        fields(body, "goal");
        String goal = text(body, "goal", 500).strip();
        sensitive(goal);
        long started = System.nanoTime();
        JsonNode response = client.post("preparation", object().put("schemaVersion", 1).put("goal", goal),
                Duration.ofSeconds(limits.getRoundSeconds()));
        long modelRoundMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        JsonNode intent = response.path("intent");
        fields(intent, "kind", "startDate", "endDate", "regionName", "imageName");
        String kind = text(intent, "kind", 20);
        ObjectNode result = object().put("modelCalls", 1).put("modelRoundMs", modelRoundMs);
        if ("UNSUPPORTED".equals(kind)) return result.put("status", "UNSUPPORTED");
        require("REGION_NDVI".equals(kind));
        LocalDate start = date(intent, "startDate");
        LocalDate end = date(intent, "endDate");
        require((start == null && end == null) || (start != null && end != null && !start.isAfter(end)));
        String regionName = optionalText(intent, "regionName", 100);
        String imageName = optionalText(intent, "imageName", 255);
        var regionMatches = regions.searchForPreparation(owner, regionName);
        Long regionId = regionMatches.size() == 1 ? regionMatches.get(0).getId() : null;
        var imageMatches = images.searchForPreparation(owner, imageName,
                start == null ? null : start.atStartOfDay(CAPTURE_ZONE).toOffsetDateTime(),
                end == null ? null : end.plusDays(1).atStartOfDay(CAPTURE_ZONE).toOffsetDateTime(), regionId);
        var imageChoices = result.putArray("images");
        RsImage uniqueImage = null;
        boolean moreImages = imageMatches.size() >= IMAGE_SCAN_LIMIT;
        for (RsImage image : imageMatches) {
            try {
                imageIdentity(image);
            } catch (BusinessException excluded) {
                continue;
            }
            if (imageChoices.size() == MAX_CHOICES) {
                moreImages = true;
                break;
            }
            uniqueImage = image;
            imageChoices.add(imageChoice(image));
        }
        var regionChoices = result.putArray("regions");
        regionMatches.stream().limit(MAX_CHOICES).forEach(region -> regionChoices.add(object()
                .put("id", region.getId()).put("name", region.getName()).put("version", region.getVersion())));
        result.put("moreImages", moreImages).put("moreRegions", regionMatches.size() > MAX_CHOICES);
        if (imageChoices.isEmpty() || regionMatches.isEmpty()) return result.put("status", "NO_MATCH");
        if (imageChoices.size() != 1 || moreImages || regionId == null) return result.put("status", "CHOOSE");
        MonitoringRegionVO region = resources.region(regionId, owner);
        if (!region.getVersion().equals(regionMatches.get(0).getVersion())) {
            throw new AgentFailure("RESOURCE_CHANGED", "监测区域已更新，请重新准备分析", 409, true);
        }
        result.set("draft", draft(uniqueImage, region));
        return result.put("status", "READY");
    }

    public ObjectNode select(JsonNode body, String owner) {
        fields(body, "imageId", "monitoringRegionId");
        RsImage image = resources.image(id(body, "imageId"), owner);
        MonitoringRegionVO region = resources.region(id(body, "monitoringRegionId"), owner);
        return draft(image, region);
    }

    /** Hash of the admitted input and the object location, independent of task state and thumbnails. */
    public static String imageIdentity(RsImage image) {
        return hash(object().put("id", image.getId()).put("bucket", image.getMinioBucket())
                .put("key", image.getObjectKey()).set("input", InputAdmission.snapshot(image.getMetadataJson(), image.getId(), NDVI_BANDS)));
    }

    private ObjectNode draft(RsImage image, MonitoringRegionVO region) {
        ObjectNode draft = imageChoice(image).put("regionId", region.getId()).put("regionName", region.getName())
                .put("regionVersion", region.getVersion()).put("imageIdentity", imageIdentity(image))
                .put("coverage", coverage(image, region));
        draft.set("bands", JSON.valueToTree(NDVI_BANDS));
        return draft;
    }

    private ObjectNode imageChoice(RsImage image) {
        return object().put("id", image.getId()).put("name", image.getImageName()).put("captureDate",
                image.getAcquisitionTime() == null ? null
                        : image.getAcquisitionTime().atZoneSameInstant(CAPTURE_ZONE).toLocalDate().toString());
    }

    private String coverage(RsImage image, MonitoringRegionVO region) {
        var manifest = InputAdmission.snapshot(image.getMetadataJson(), image.getId(), NDVI_BANDS).path("manifest");
        String crs = manifest.path("crs").asText();
        var transform = manifest.path("transform");
        int width = manifest.path("width").asInt(), height = manifest.path("height").asInt();
        if (!crs.matches("EPSG:[1-9][0-9]{0,5}") || !transform.isArray() || transform.size() != 6
                || width <= 0 || height <= 0) return "UNKNOWN";
        double[] t = new double[6];
        for (int i = 0; i < t.length; i++) {
            if (!transform.get(i).isNumber() || !Double.isFinite(transform.get(i).doubleValue())) return "UNKNOWN";
            t[i] = transform.get(i).doubleValue();
        }
        double determinant = t[0] * t[4] - t[1] * t[3];
        if (!Double.isFinite(determinant) || determinant == 0) return "UNKNOWN";
        double a = t[4] / determinant, b = -t[1] / determinant;
        double d = -t[3] / determinant, e = t[0] / determinant;
        try {
            return regions.rasterCoverage(region.getGeometryJson(), Integer.parseInt(crs.substring(5)), width, height,
                    a, b, d, e, -(a * t[2] + b * t[5]), -(d * t[2] + e * t[5]));
        } catch (DataAccessException unavailable) {
            return "UNKNOWN";
        }
    }

    private static LocalDate date(JsonNode intent, String field) {
        String value = optionalText(intent, field, 10);
        if (value == null) return null;
        require(value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"));
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            throw AgentProtocol.invalid();
        }
    }
}
