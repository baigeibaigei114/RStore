package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.dto.RsImageSearchDTO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RsImageMapper {

    /**
     * 插入后回填自增主键，缩略图 objectKey 需要依赖该 imageId 生成。
     */
    int insert(RsImage image);

    RsImage selectById(@Param("id") Long id);

    List<RsImage> selectPage(@Param("offset") int offset, @Param("pageSize") int pageSize);

    List<RsImage> searchPage(@Param("query") RsImageSearchDTO query,
                             @Param("offset") int offset,
                             @Param("pageSize") int pageSize);

    List<RsImage> searchByRegionPage(@Param("query") RsImageSearchDTO query,
                                     @Param("offset") int offset,
                                     @Param("pageSize") int pageSize);

    long count();

    long countSearch(@Param("query") RsImageSearchDTO query);

    long countSearchByRegion(@Param("query") RsImageSearchDTO query);

    int deleteById(@Param("id") Long id);

    long countByImageCode(@Param("imageCode") String imageCode);

    /**
     * 缩略图生成发生在影像记录插入之后，因此单独回写该字段。
     */
    int updateThumbnailObjectKey(@Param("id") Long id, @Param("thumbnailObjectKey") String thumbnailObjectKey);
}
