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

/**
 * 行政区划查询控制器。
 * <p>提供省/市/县/乡四级行政区划的树形查询、级别筛选、详情查看和关键字搜索功能。
 * 行政区划数据来源于标准民政部数据，经过空间 ETL 导入 PostGIS。</p>
 */
@RestController
@RequestMapping("/admin-regions")
public class AdminRegionController {

    private final AdminRegionService adminRegionService;

    public AdminRegionController(AdminRegionService adminRegionService) {
        this.adminRegionService = adminRegionService;
    }

    /**
     * 查询指定父节点下的直接子级行政区划列表。
     * <p>典型场景：选择省份后加载其下属城市列表。
     * parentId 为空时返回省级节点（根节点），
     * 避免一次性加载全部数据导致前端渲染压力。</p>
     *
     * @param parentId 父级行政区划 ID，为空时查询省级
     * @return 子级行政区划列表，按行政编码排序
     */
    @GetMapping("/children")
    public Result<List<AdminRegionVO>> listChildren(@RequestParam(required = false) Long parentId) {
        return Result.success(adminRegionService.listChildren(parentId));
    }

    /**
     * 按行政级别查询行政区划列表。
     * <p>level 取值如 "province"、"city"、"district"、"town"。
     * level 为空时默认返回所有级别数据。</p>
     *
     * @param level 行政级别编码，可选
     * @return 匹配级别的行政区划列表
     */
    @GetMapping
    public Result<List<AdminRegionVO>> listByLevel(@RequestParam(required = false) String level) {
        return Result.success(adminRegionService.listByLevel(level));
    }

    /**
     * 查询行政区划详情，包含空间边界（GeoJSON）。
     * <p>simplifyTolerance 参数控制边界简化程度（单位：度），
     * 用于前端地图展示时降低 GeoJSON 数据量，提升渲染性能。
     * 不传时返回原始精度边界。</p>
     *
     * @param id                 行政区划主键 ID
     * @param simplifyTolerance  道格拉斯-普克简化容差（可选），值越大边界越粗糙
     * @return 行政区划详情 VO，含名称、级别、中心坐标、GeoJSON 边界
     */
    @GetMapping("/{id}")
    public Result<AdminRegionDetailVO> getDetail(@PathVariable Long id,
                                                 @RequestParam(required = false) Double simplifyTolerance) {
        return Result.success(adminRegionService.getDetail(id, simplifyTolerance));
    }

    /**
     * 按关键字模糊搜索行政区划。
     * <p>常用于输入框自动补全场景，匹配行政区划名称。
     * 搜索在服务端执行，使用数据库 LIKE 或全文索引能力。</p>
     *
     * @param keyword 搜索关键字，至少需提供 1 个字符
     * @return 匹配的行政区划列表
     */
    @GetMapping("/search")
    public Result<List<AdminRegionVO>> search(@RequestParam String keyword) {
        return Result.success(adminRegionService.search(keyword));
    }
}
