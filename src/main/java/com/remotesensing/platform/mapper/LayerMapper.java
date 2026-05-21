package com.remotesensing.platform.mapper;

import com.remotesensing.platform.dto.LayerSearchDTO;
import com.remotesensing.platform.vo.LayerVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 图层（rs_result_file 发布视图）Mapper 接口。
 * 查询已发布到 GeoServer 的图层信息，通过多表 JOIN 组装图层列表数据。
 */
@Mapper
public interface LayerMapper {

    /**
     * 分页查询当前用户可访问的已发布图层列表。
     * 支持按任务类型、关联影像 ID 和关键词筛选。
     * 通过 JOIN rs_result_file、rs_task、rs_image 组装完整图层信息。
     *
     * @param query          搜索条件 DTO
     * @param offset         分页偏移量
     * @param pageSize       每页数量
     * @param currentUserId  当前用户 ID
     * @return 图层 VO 集合
     */
    List<LayerVO> selectPage(@Param("query") LayerSearchDTO query,
                             @Param("offset") int offset,
                             @Param("pageSize") int pageSize,
                             @Param("currentUserId") String currentUserId);

    /**
     * 统计当前用户可访问的已发布图层总数。
     *
     * @param query          搜索条件 DTO
     * @param currentUserId  当前用户 ID
     * @return 图层总数
     */
    long count(@Param("query") LayerSearchDTO query,
               @Param("currentUserId") String currentUserId);

    /**
     * 查询当前用户可访问的已发布图层详情。
     * 仅返回已发布（PUBLISHED）且对当前用户可见的图层。
     *
     * @param id             图层主键（rs_result_file.id）
     * @param currentUserId  当前用户 ID
     * @return 图层 VO，无权访问或未发布时返回 null
     */
    LayerVO selectAccessiblePublishedById(@Param("id") Long id,
                                          @Param("currentUserId") String currentUserId);
}