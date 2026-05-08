package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsImage;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RsImageMapper {

    int insert(RsImage image);

    RsImage selectById(@Param("id") Long id);

    List<RsImage> selectPage(@Param("offset") int offset, @Param("pageSize") int pageSize);

    long count();

    int deleteById(@Param("id") Long id);

    long countByImageCode(@Param("imageCode") String imageCode);

    int updateThumbnailObjectKey(@Param("id") Long id, @Param("thumbnailObjectKey") String thumbnailObjectKey);
}
