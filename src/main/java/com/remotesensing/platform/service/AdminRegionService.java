package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.AdminRegionDetailVO;
import com.remotesensing.platform.vo.AdminRegionVO;
import java.util.List;

public interface AdminRegionService {

    List<AdminRegionVO> listChildren(Long parentId);

    List<AdminRegionVO> listByLevel(String level);

    AdminRegionDetailVO getDetail(Long id, Double simplifyTolerance);

    List<AdminRegionVO> search(String keyword);
}
