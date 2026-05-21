package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.AdminRegion;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 行政区划（admin_region）Mapper 接口。
 * 提供行政区划的层级查询、级别筛选、边界详情查询及关键词搜索功能。
 * 基于 PostGIS 空间数据库存储行政区边界数据。
 */
@Mapper
public interface AdminRegionMapper {

    /**
     * 查询指定父级下的所有子级行政区划。
     * 例如查询某个省份下的所有城市，或某个城市下的所有区县。
     *
     * @param parentId 父级行政区划 ID
     * @return 子级行政区划集合
     */
    List<AdminRegion> selectChildren(@Param("parentId") Long parentId);

    /**
     * 根据行政级别查询行政区划列表。
     *
     * @param level 级别：province（省）/ city（市）/ district（区县）
     * @return 匹配的行政区划集合
     */
    List<AdminRegion> selectByLevel(@Param("level") String level);

    /**
     * 查询行政区划详情，包含空间边界（GeoJSON 格式）。
     * 支持通过 simplifyTolerance 参数简化边界几何，减少传输数据量。
     *
     * @param id                行政区划主键
     * @param simplifyTolerance 简化容差（地图单位），用于 ST_Simplify 函数
     * @return 行政区划详情实体，包含 boundary 字段
     */
    AdminRegion selectDetailById(@Param("id") Long id, @Param("simplifyTolerance") double simplifyTolerance);

    /**
     * 按关键词模糊搜索行政区划名称。
     *
     * @param keyword 搜索关键词
     * @return 匹配的行政区划集合
     */
    List<AdminRegion> searchByKeyword(@Param("keyword") String keyword);
}