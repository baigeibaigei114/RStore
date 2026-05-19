package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.service.AdminRegionService;
import com.remotesensing.platform.vo.AdminRegionDetailVO;
import com.remotesensing.platform.vo.AdminRegionVO;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin-regions")
public class AdminRegionController {

    private final AdminRegionService adminRegionService;

    public AdminRegionController(AdminRegionService adminRegionService) {
        this.adminRegionService = adminRegionService;
    }

    @GetMapping("/children")
    public Result<List<AdminRegionVO>> listChildren(@RequestParam(required = false) Long parentId) {
        return Result.success(adminRegionService.listChildren(parentId));
    }

    @GetMapping
    public Result<List<AdminRegionVO>> listByLevel(@RequestParam(required = false) String level) {
        return Result.success(adminRegionService.listByLevel(level));
    }

    @GetMapping("/{id}")
    public Result<AdminRegionDetailVO> getDetail(@PathVariable Long id,
                                                 @RequestParam(required = false) Double simplifyTolerance) {
        return Result.success(adminRegionService.getDetail(id, simplifyTolerance));
    }

    @GetMapping("/search")
    public Result<List<AdminRegionVO>> search(@RequestParam String keyword) {
        return Result.success(adminRegionService.search(keyword));
    }
}
