package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.dto.MonitoringRegionDTO;
import com.remotesensing.platform.service.MonitoringRegionService;
import com.remotesensing.platform.vo.MonitoringRegionVO;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/monitoring-regions")
public class MonitoringRegionController {
    private final MonitoringRegionService service;
    public MonitoringRegionController(MonitoringRegionService service) { this.service = service; }

    @GetMapping
    public Result<List<MonitoringRegionVO>> list() { return Result.success(service.list()); }

    @GetMapping("/{id}")
    public Result<MonitoringRegionVO> get(@PathVariable Long id) { return Result.success(service.get(id)); }

    @PostMapping
    public Result<MonitoringRegionVO> create(@Valid @RequestBody MonitoringRegionDTO dto) {
        return Result.success(service.save(null, dto));
    }

    @PutMapping("/{id}")
    public Result<MonitoringRegionVO> update(@PathVariable Long id, @Valid @RequestBody MonitoringRegionDTO dto) {
        return Result.success(service.save(id, dto));
    }
}
