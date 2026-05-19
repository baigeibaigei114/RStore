package com.remotesensing.platform.mapper;

import com.remotesensing.platform.dto.LayerSearchDTO;
import com.remotesensing.platform.vo.LayerVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface LayerMapper {

    List<LayerVO> selectPage(@Param("query") LayerSearchDTO query,
                             @Param("offset") int offset,
                             @Param("pageSize") int pageSize,
                             @Param("currentUserId") String currentUserId);

    long count(@Param("query") LayerSearchDTO query,
               @Param("currentUserId") String currentUserId);

    LayerVO selectAccessiblePublishedById(@Param("id") Long id,
                                          @Param("currentUserId") String currentUserId);
}
