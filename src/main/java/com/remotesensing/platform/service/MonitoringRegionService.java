package com.remotesensing.platform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.dto.MonitoringRegionDTO;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.MonitoringRegionMapper;
import com.remotesensing.platform.vo.MonitoringRegionVO;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonitoringRegionService {
    private final MonitoringRegionMapper mapper;
    private final CurrentUserContext users;
    private final ObjectMapper json;

    public MonitoringRegionService(MonitoringRegionMapper mapper, CurrentUserContext users, ObjectMapper json) {
        this.mapper = mapper;
        this.users = users;
        this.json = json;
    }

    public List<MonitoringRegionVO> list() {
        return mapper.list(users.getCurrentUserId());
    }

    public MonitoringRegionVO get(Long id) {
        MonitoringRegionVO region = mapper.find(id, users.getCurrentUserId());
        require(region != null, "区域不存在或无权访问");
        return region;
    }

    @Transactional
    public MonitoringRegionVO save(Long id, MonitoringRegionDTO dto) {
        if (id != null) get(id);
        require(dto.getName() != null && !dto.getName().isBlank() && dto.getName().length() <= 100,
                "区域名称应为 1 至 100 字符");
        validateGeometry(dto.getGeometry());
        // 只传几何核心字段，避免客户端 crs 覆盖固定的 EPSG:4326 约定。
        ObjectNode geometry = json.createObjectNode().put("type", "Polygon");
        geometry.set("coordinates", dto.getGeometry().get("coordinates"));
        require(mapper.valid(geometry.toString()), "区域自交、退化或为空，请重新绘制");
        MonitoringRegionVO region = new MonitoringRegionVO();
        region.setId(id);
        region.setName(dto.getName().trim());
        region.setGeometryJson(geometry.toString());
        if (id == null) {
            mapper.insert(users.getCurrentUserId(), region);
        } else {
            require(mapper.update(users.getCurrentUserId(), region) == 1, "区域不存在或无权访问");
        }
        return get(region.getId());
    }

    public ObjectNode snapshot(Long id) {
        MonitoringRegionVO region = get(id);
        ObjectNode result = json.createObjectNode()
                .put("schemaVersion", 1).put("regionId", region.getId()).put("name", region.getName())
                .put("version", region.getVersion()).put("crs", "EPSG:4326")
                .put("pixelRule", "center_covered");
        try {
            result.set("geometry", json.readTree(region.getGeometryJson()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored monitoring region geometry is invalid", e);
        }
        return result;
    }

    static void validateGeometry(JsonNode geometry) {
        require(geometry != null && "Polygon".equals(geometry.path("type").asText()), "仅支持 Polygon");
        JsonNode rings = geometry.path("coordinates");
        require(rings.isArray() && rings.size() == 1, "仅支持一个外环，不支持洞");
        JsonNode ring = rings.get(0);
        require(ring.isArray() && ring.size() >= 4 && ring.size() <= 501, "区域应有 3 至 500 个顶点");
        double minX = 180, maxX = -180;
        for (JsonNode point : ring) {
            require(point.isArray() && point.size() == 2, "坐标必须为二维经纬度");
            for (JsonNode value : point) {
                require(value.isNumber() && Double.isFinite(value.doubleValue()), "坐标必须为有限数值");
            }
            double x = point.get(0).doubleValue(), y = point.get(1).doubleValue();
            require(Math.abs(x) <= 180 && Math.abs(y) <= 85, "经纬度超出支持范围");
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
        }
        JsonNode first = ring.get(0), last = ring.get(ring.size() - 1);
        require(first.get(0).doubleValue() == last.get(0).doubleValue()
                && first.get(1).doubleValue() == last.get(1).doubleValue(), "区域必须闭合");
        require(maxX - minX <= 180, "不支持跨日期变更线区域");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), message);
    }
}
