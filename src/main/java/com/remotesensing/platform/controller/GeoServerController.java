package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.common.CurrentUserContext;
import com.remotesensing.platform.config.properties.RateLimitProperties;
import com.remotesensing.platform.service.GeoServerService;
import com.remotesensing.platform.service.RateLimitService;
import com.remotesensing.platform.vo.GeoServerPublishVO;
import java.time.Duration;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GeoServer 发布管理控制器。
 * <p>提供遥感处理任务结果到 GeoServer 图层的发布能力。
 * 实际发布操作通过 GeoServer REST API 完成，包括创建数据存储、注册图层、
 * 配置样式等步骤。此控制器也可作为手动触发数据同步的入口。</p>
 */
@RestController
@RequestMapping("/geoserver")
public class GeoServerController {

    private final GeoServerService geoServerService;
    private final RateLimitService rateLimitService;
    private final RateLimitProperties rateLimitProperties;
    private final CurrentUserContext currentUserContext;

    public GeoServerController(GeoServerService geoServerService,
                               RateLimitService rateLimitService,
                               RateLimitProperties rateLimitProperties,
                               CurrentUserContext currentUserContext) {
        this.geoServerService = geoServerService;
        this.rateLimitService = rateLimitService;
        this.rateLimitProperties = rateLimitProperties;
        this.currentUserContext = currentUserContext;
    }

    /**
     * 将指定任务的输出结果发布为 GeoServer 图层。
     * <p>手动触发入口，适用于自动发布失败后的补偿操作。
     * 发布流程包括：从 MinIO 获取结果 GeoTIFF -> 通过 GeoServer REST API
     * 创建 CoverageStore -> 发布为图层 -> 关联默认样式。
     * 前端可通过任务详情页的"重新发布"按钮调用此接口。</p>
     *
     * @param taskId 已完成的任务 ID，其输出结果将被发布
     * @return 发布结果 VO，包含 GeoServer 图层名称和访问地址
     */
    @PostMapping("/publish/{taskId}")
    public Result<GeoServerPublishVO> publishTaskResult(@PathVariable Long taskId) {
        checkPublishRateLimit();
        return Result.success(geoServerService.publishTaskResult(taskId));
    }

    private void checkPublishRateLimit() {
        String userId = currentUserContext.getCurrentUserId();
        rateLimitService.check(
                "geoserver-publish:user:" + userId,
                rateLimitProperties.getGeoserverPublishLimit(),
                Duration.ofSeconds(rateLimitProperties.getGeoserverPublishWindowSeconds())
        );
    }
}
