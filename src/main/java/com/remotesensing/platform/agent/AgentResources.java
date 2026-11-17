package com.remotesensing.platform.agent;

import com.remotesensing.platform.common.InputAdmission;
import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.mapper.*;
import com.remotesensing.platform.vo.MonitoringRegionVO;
import java.util.*;
import org.springframework.stereotype.Component;

/** Reuses owner-filtered business lookups; the preparation flow never delegates business access to Python. */
@Component
public class AgentResources {
    private final RsImageMapper images;
    private final MonitoringRegionMapper regions;
    public AgentResources(RsImageMapper images, MonitoringRegionMapper regions) { this.images = images; this.regions = regions; }
    public RsImage image(long id, String owner) {
        RsImage image = images.selectAccessibleById(id, owner);
        if (image == null || image.getDeletedAt() != null || !(owner.equals(image.getOwnerId()) || "PUBLIC".equals(image.getVisibility())))
            throw AgentFailure.conflict("RESOURCE_UNAVAILABLE");
        if (!"READY".equals(image.getStatus())) throw AgentFailure.conflict("RESOURCE_NOT_READY");
        InputAdmission.snapshot(image.getMetadataJson(), image.getId(), Map.of("redBand", 3, "nirBand", 4));
        return image;
    }
    public MonitoringRegionVO region(long id, String owner) {
        MonitoringRegionVO region = regions.find(id, owner);
        if (region == null) throw AgentFailure.conflict("RESOURCE_UNAVAILABLE");
        return region;
    }
}
