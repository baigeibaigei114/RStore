package com.remotesensing.platform.controller;

import com.remotesensing.platform.common.PageResult;
import com.remotesensing.platform.common.Result;
import com.remotesensing.platform.dto.LayerSearchDTO;
import com.remotesensing.platform.service.LayerService;
import com.remotesensing.platform.vo.LayerVO;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/layers")
public class LayerController {

    private final LayerService layerService;

    public LayerController(LayerService layerService) {
        this.layerService = layerService;
    }

    @GetMapping
    public Result<PageResult<LayerVO>> page(@RequestParam(required = false) Integer pageNum,
                                            @RequestParam(required = false) Integer pageSize,
                                            @RequestParam(required = false) String taskType,
                                            @RequestParam(required = false) Long imageId,
                                            @RequestParam(required = false) String keyword) {
        LayerSearchDTO query = new LayerSearchDTO();
        query.setTaskType(taskType);
        query.setImageId(imageId);
        query.setKeyword(keyword);
        return Result.success(layerService.page(query, pageNum, pageSize));
    }

    @GetMapping("/{id}/wms")
    public ResponseEntity<byte[]> proxyWms(@PathVariable Long id,
                                           @RequestParam MultiValueMap<String, String> queryParams) {
        return layerService.proxyWms(id, queryParams);
    }

    @GetMapping("/{id}/wcs")
    public ResponseEntity<byte[]> proxyWcs(@PathVariable Long id,
                                           @RequestParam MultiValueMap<String, String> queryParams) {
        return layerService.proxyWcs(id, queryParams);
    }
}
