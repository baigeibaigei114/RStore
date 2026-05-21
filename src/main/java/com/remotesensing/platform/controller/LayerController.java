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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 图层查询与 OGC 服务代理控制器。
 * <p>提供已发布图层的列表查询，以及 WMS（Web Map Service）和 WCS（Web Coverage Service）
 * 的代理转发功能。代理转发是为了隐藏后端 GeoServer 的真实地址和端口，
 * 统一通过应用层出口暴露 OGC 服务。</p>
 */
@RestController
@RequestMapping("/layers")
public class LayerController {

    private final LayerService layerService;

    public LayerController(LayerService layerService) {
        this.layerService = layerService;
    }

    /**
     * 分页查询已发布的图层列表。
     * <p>支持按任务类型、关联影像 ID、关键字等条件筛选。
     * 结果中的图层均为已发布到 GeoServer 且在前端可见的图层。</p>
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @param taskType 关联的任务类型筛选（可选），如 NDVI、MNDWI
     * @param imageId  关联的源影像 ID 筛选（可选）
     * @param keyword  关键字模糊搜索（可选），匹配图层名称
     * @return 分页结果，包含图层概要信息列表
     */
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

    /**
     * WMS 代理：将前端的 WMS 请求转发到 GeoServer。
     * <p>通过 id 定位图层在 GeoServer 中的完整名称，将查询参数透传至 GeoServer，
     * 并将响应以流式方式返回前端。流式传输（StreamingResponseBody）避免大图片
     * 在代理层全量缓冲，降低内存开销。此代理也起到防火墙作用：
     * 前端不感知 GeoServer 内网地址，减少攻击面。</p>
     *
     * @param id          图层主键 ID
     * @param queryParams 原始 WMS 请求参数（SERVICE、VERSION、REQUEST、LAYERS、BBOX、WIDTH、HEIGHT 等）
     * @return GeoServer 原始响应（通常是 PNG/JPEG 图片或 XML），通过流式响应体返回
     */
    @GetMapping("/{id}/wms")
    public ResponseEntity<StreamingResponseBody> proxyWms(@PathVariable Long id,
                                                          @RequestParam MultiValueMap<String, String> queryParams) {
        return layerService.proxyWms(id, queryParams);
    }

    /**
     * WCS 代理：将前端的 WCS 请求转发到 GeoServer。
     * <p>与 WMS 代理类似，但 WCS 返回的是原始栅格数据（GeoTIFF 等）而非图片，
     * 适用于客户端需要下载原始影像数据进行本地分析的场景。
     * 同样使用流式传输减少内存占用。</p>
     *
     * @param id          图层主键 ID
     * @param queryParams 原始 WCS 请求参数（COVERAGEID、FORMAT、SUBSET 等）
     * @return GeoServer 原始响应（通常是 GeoTIFF 二进制流），通过流式响应体返回
     */
    @GetMapping("/{id}/wcs")
    public ResponseEntity<StreamingResponseBody> proxyWcs(@PathVariable Long id,
                                                          @RequestParam MultiValueMap<String, String> queryParams) {
        return layerService.proxyWcs(id, queryParams);
    }
}
