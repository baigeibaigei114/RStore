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

@Service
public class AdminRegionServiceImpl implements AdminRegionService {

    private static final double DEFAULT_SIMPLIFY_TOLERANCE = 0.001D;

    private final AdminRegionMapper adminRegionMapper;

    public AdminRegionServiceImpl(AdminRegionMapper adminRegionMapper) {
        this.adminRegionMapper = adminRegionMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminRegionVO> listChildren(Long parentId) {
        return adminRegionMapper.selectChildren(parentId).stream()
                .map(this::toVO)
                .toList();
    }

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

    @Override
    @Transactional(readOnly = true)
    public AdminRegionDetailVO getDetail(Long id, Double simplifyTolerance) {
        AdminRegion region = adminRegionMapper.selectDetailById(id, normalizeSimplifyTolerance(simplifyTolerance));
        if (region == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "行政区不存在");
        }
        return toDetailVO(region);
    }

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

    private AdminRegionVO toVO(AdminRegion region) {
        AdminRegionVO vo = new AdminRegionVO();
        vo.setId(region.getId());
        vo.setAdcode(region.getAdcode());
        vo.setName(region.getName());
        vo.setLevel(region.getLevel());
        vo.setParentId(region.getParentId());
        return vo;
    }

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
