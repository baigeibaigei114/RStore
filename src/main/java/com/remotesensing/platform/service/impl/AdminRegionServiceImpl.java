package com.remotesensing.platform.service.impl;

import com.remotesensing.platform.common.ResultCode;
import com.remotesensing.platform.entity.AdminRegion;
import com.remotesensing.platform.exception.BusinessException;
import com.remotesensing.platform.mapper.AdminRegionMapper;
import com.remotesensing.platform.service.AdminRegionService;
import com.remotesensing.platform.vo.AdminRegionDetailVO;
import com.remotesensing.platform.vo.AdminRegionVO;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 行政区划服务实现类，提供行政区层级查询、边界详情获取和关键字搜索功能。
 *
 * <p>核心职责：
 * <ol>
 *   <li>根据父级 ID 查询子级行政区列表（用于级联选择器）。</li>
 *   <li>根据层级（province / city / district）查询行政区列表。</li>
 *   <li>获取指定行政区的边界几何（GeoJSON），支持 ST_Simplify 简化以降低传输量。</li>
 *   <li>根据关键词模糊搜索行政区。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>所有查询方法均为只读事务（{@code @Transactional(readOnly = true)}），
 *       利用数据库的只读事务优化（如 PostgreSQL 的 snapshot 复用）。</li>
 *   <li>边界查询使用 ST_Simplify 进行几何简化，默认 tolerance 为 0.001 度，
 *       在精度和性能之间取得平衡，避免在 1:50000 以下比例尺时传输过多顶点。</li>
 *   <li>简化参数 tolerance 以度为单位（SRID=4326），与地图显示坐标系一致。</li>
 * </ul>
 *
 * @author remote-sensing-platform
 */
@Service
public class AdminRegionServiceImpl implements AdminRegionService {

    /** 默认的 ST_Simplify 简化容差（单位：度），对应 SRID 4326 下约 100 米 */
    private static final double DEFAULT_SIMPLIFY_TOLERANCE = 0.001D;

    private final AdminRegionMapper adminRegionMapper;

    public AdminRegionServiceImpl(AdminRegionMapper adminRegionMapper) {
        this.adminRegionMapper = adminRegionMapper;
    }

    /**
     * 查询指定父级行政区下的子级列表。
     * <p>parentId 为 null 时返回所有顶级行政区（省级）。
     *
     * @param parentId 父级行政区 ID，null 表示查询顶级
     * @return 子级行政区视图列表（不含边界几何，仅包含基础信息）
     */
    @Override
    @Transactional(readOnly = true)
    public List<AdminRegionVO> listChildren(Long parentId) {
        return adminRegionMapper.selectChildren(parentId).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 根据层级查询行政区列表。
     * <p>level 为 null 或空白时返回所有顶级行政区（等价于 {@code listChildren(null)}）。
     *
     * @param level 行政区层级，如 province / city / district
     * @return 匹配的行政区视图列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<AdminRegionVO> listByLevel(String level) {
        if (level == null || level.isBlank()) {
            return adminRegionMapper.selectChildren(null).stream()
                    .map(this::toVO)
                    .toList();
        }
        return adminRegionMapper.selectByLevel(level.trim()).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 获取指定行政区的详细信息，包含边界几何（GeoJSON）。
     *
     * <p>边界几何使用 PostGIS ST_Simplify 进行简化，降低传输数据量。
     * 简化容差可自定义，未指定时使用默认值 {@link #DEFAULT_SIMPLIFY_TOLERANCE}（0.001 度）。
     *
     * <p>事务属性：只读事务。
     *
     * @param id                行政区 ID
     * @param simplifyTolerance ST_Simplify 简化容差（度），null 时使用默认值
     * @return 行政区详细信息（含边界 GeoJSON）
     * @throws BusinessException 如果行政区不存在
     */
    @Override
    @Transactional(readOnly = true)
    public AdminRegionDetailVO getDetail(Long id, Double simplifyTolerance) {
        AdminRegion region = adminRegionMapper.selectDetailById(id, normalizeSimplifyTolerance(simplifyTolerance));
        if (region == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "行政区不存在");
        }
        return toDetailVO(region);
    }

    /**
     * 根据关键词模糊搜索行政区。
     * <p>关键词为空或空白时返回空列表（不执行全表扫描）。
     * 搜索使用 ILIKE 或全文索引实现大小写不敏感的模糊匹配。
     *
     * @param keyword 搜索关键词
     * @return 匹配的行政区视图列表
     */
    @Override
    @Transactional(readOnly = true)
    public List<AdminRegionVO> search(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return adminRegionMapper.searchByKeyword(keyword.trim()).stream()
                .map(this::toVO)
                .toList();
    }

    /**
     * 将 {@link AdminRegion} 实体转换为基础的视图对象（不含边界几何）。
     */
    private AdminRegionVO toVO(AdminRegion region) {
        AdminRegionVO vo = new AdminRegionVO();
        vo.setId(region.getId());
        vo.setAdcode(region.getAdcode());
        vo.setName(region.getName());
        vo.setLevel(region.getLevel());
        vo.setParentId(region.getParentId());
        return vo;
    }

    /**
     * 将 {@link AdminRegion} 实体转换为详细视图对象（含边界几何 GeoJSON）。
     */
    private AdminRegionDetailVO toDetailVO(AdminRegion region) {
        AdminRegionDetailVO vo = new AdminRegionDetailVO();
        vo.setId(region.getId());
        vo.setAdcode(region.getAdcode());
        vo.setName(region.getName());
        vo.setLevel(region.getLevel());
        vo.setParentId(region.getParentId());
        vo.setBoundaryGeoJson(region.getBoundaryGeoJson());
        return vo;
    }

    /**
     * 归一化 ST_Simplify 简化容差。
     * <p>为 null 时使用默认值 0.001，小于 0 时抛出异常（负容差会导致几何变形）。
     *
     * @param simplifyTolerance 简化容差（度）
     * @return 归一化后的容差值
     */
    private double normalizeSimplifyTolerance(Double simplifyTolerance) {
        if (simplifyTolerance == null) {
            return DEFAULT_SIMPLIFY_TOLERANCE;
        }
        if (simplifyTolerance < 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "simplifyTolerance 不能小于 0");
        }
        return simplifyTolerance;
    }
}
