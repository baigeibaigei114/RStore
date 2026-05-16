package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.service.GeoServerService;
import com.remotesensing.platform.vo.GeoServerPublishVO;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/geoserver")
public class GeoServerController {

    private final GeoServerService geoServerService;

    public GeoServerController(GeoServerService geoServerService) {
        this.geoServerService = geoServerService;
    }

    /**
     * 手动测试发布入口：任务成功后，可将输出 GeoTIFF 注册为 GeoServer 图层。
     */
    @PostMapping("/publish/{taskId}")
    public Result<GeoServerPublishVO> publishTaskResult(@PathVariable Long taskId) {
        return Result.success(geoServerService.publishTaskResult(taskId));
    }
}
