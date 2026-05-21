package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.AdminRegionDetailVO;
import com.remotesensing.platform.vo.AdminRegionVO;
import java.util.List;

/**
 * 行政区划服务接口。
 * <p>
 * 提供行政区划的层级查询、按级别查询、边界详情查询和关键字搜索功能。
 * 用于影像检索时的行政区域筛选器。
 */
public interface AdminRegionService {

    /**
     * 查询指定父级下的子级行政区划列表。
     *
     * @param parentId 父级行政区划 ID（null 表示根节点）
     * @return 子级行政区划列表
     */
    List<AdminRegionVO> listChildren(Long parentId);

    /**
     * 按行政级别查询行政区划列表。
     *
     * @param level 行政级别：province / city / district / street
     * @return 对应级别的行政区划列表
     */
    List<AdminRegionVO> listByLevel(String level);

    /**
     * 获取行政区划详情（含边界 GeoJSON）。
     *
     * @param id                行政区划主键 ID
     * @param simplifyTolerance 边界简化容差（米），用于控制返回的边界精度
     * @return 行政区划详情
     */
    AdminRegionDetailVO getDetail(Long id, Double simplifyTolerance);

    /**
     * 按关键字搜索行政区划。
     *
     * @param keyword 搜索关键字（匹配名称）
     * @return 匹配的行政区划列表
     */
    List<AdminRegionVO> search(String keyword);
}
