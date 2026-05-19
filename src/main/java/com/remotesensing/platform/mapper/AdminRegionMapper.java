package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.AdminRegion;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdminRegionMapper {

    List<AdminRegion> selectChildren(@Param("parentId") Long parentId);

    List<AdminRegion> selectByLevel(@Param("level") String level);

    AdminRegion selectDetailById(@Param("id") Long id, @Param("simplifyTolerance") double simplifyTolerance);

    List<AdminRegion> searchByKeyword(@Param("keyword") String keyword);
}
