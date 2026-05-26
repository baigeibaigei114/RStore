package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsImage;
import com.remotesensing.platform.dto.RsImageSearchDTO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 遥感影像（rs_image）Mapper 接口。
 * 提供影像的增删改查、空间搜索、缩略图状态管理、软删除及状态流转等操作。
 */
@Mapper
public interface RsImageMapper {

    /**
     * 插入一条新的影像记录。
     * 插入后 MyBatis 回填自增主键 id，缩略图 objectKey 需要依赖该 imageId 生成。
     *
     * @param image 影像实体
     * @return 受影响行数
     */
    int insert(RsImage image);

    /**
     * 根据主键 ID 查询影像。
     *
     * @param id 影像主键
     * @return 影像实体，不存在返回 null
     */
    RsImage selectById(@Param("id") Long id);

    /**
     * 根据主键 ID 查询当前用户可访问的影像（公开或自己的私有影像）。
     *
     * @param id             影像主键
     * @param currentUserId  当前用户 ID
     * @return 影像实体，无权访问时返回 null
     */
    RsImage selectAccessibleById(@Param("id") Long id,
                                 @Param("currentUserId") String currentUserId);

    /**
     * 分页查询当前用户可访问的影像列表。
     * 返回公开影像 + 当前用户自己的私有影像。
     *
     * @param offset        分页偏移量
     * @param pageSize      每页数量
     * @param currentUserId 当前用户 ID
     * @return 影像实体集合
     */
    List<RsImage> selectPage(@Param("offset") int offset,
                             @Param("pageSize") int pageSize,
                             @Param("currentUserId") String currentUserId);

    /**
     * 分页搜索影像，支持关键词、时间范围、传感器和云量等条件筛选。
     *
     * @param query         搜索条件 DTO
     * @param offset        分页偏移量
     * @param pageSize      每页数量
     * @param currentUserId 当前用户 ID
     * @return 匹配条件的影像实体集合
     */
    List<RsImage> searchPage(@Param("query") RsImageSearchDTO query,
                             @Param("offset") int offset,
                             @Param("pageSize") int pageSize,
                             @Param("currentUserId") String currentUserId);

    /**
     * 按空间范围（边界框 + 行政区）分页搜索影像。
     * 使用 PostGIS 空间索引进行 footprint 与区域边界的相交判断。
     *
     * @param query         搜索条件 DTO
     * @param offset        分页偏移量
     * @param pageSize      每页数量
     * @param currentUserId 当前用户 ID
     * @return 匹配条件的影像实体集合
     */
    List<RsImage> searchByRegionPage(@Param("query") RsImageSearchDTO query,
                                     @Param("offset") int offset,
                                     @Param("pageSize") int pageSize,
                                     @Param("currentUserId") String currentUserId);

    /**
     * 统计当前用户可访问的影像总数。
     *
     * @param currentUserId 当前用户 ID
     * @return 影像总数
     */
    long count(@Param("currentUserId") String currentUserId);

    /**
     * 统计满足搜索条件的影像总数。
     *
     * @param query         搜索条件 DTO
     * @param currentUserId 当前用户 ID
     * @return 匹配数量
     */
    long countSearch(@Param("query") RsImageSearchDTO query,
                     @Param("currentUserId") String currentUserId);

    /**
     * 统计满足空间搜索条件的影像总数。
     *
     * @param query         搜索条件 DTO
     * @param currentUserId 当前用户 ID
     * @return 匹配数量
     */
    long countSearchByRegion(@Param("query") RsImageSearchDTO query,
                             @Param("currentUserId") String currentUserId);

    /**
     * 查询待生成缩略图的影像 ID 列表。
     *
     * @param limit 最大返回数量
     * @return 待生成缩略图的影像 ID 集合
     */
    List<Long> selectPendingThumbnailImageIds(@Param("limit") int limit);

    /**
     * 对可删除的影像执行软删除（设置 deleted_at 和 deleted_reason）。
     * 仅删除 OWNER 本人的且未被任务引用的影像。
     *
     * @param id            影像主键
     * @param ownerId       所有者用户 ID
     * @param deletedBy     删除者标识
     * @param deletedReason 删除原因
     * @return 受影响行数（0 表示不可删除或已被删除）
     */
    int softDeleteIfDeletable(@Param("id") Long id,
                              @Param("ownerId") String ownerId,
                              @Param("deletedBy") String deletedBy,
                              @Param("deletedReason") String deletedReason);

    /**
     * 将影像状态从 READY 更新为 PROCESSING。
     * 提交任务时用条件更新占用影像，避免删除和任务创建之间出现并发窗口。
     *
     * @param id 影像主键
     * @return 受影响行数（0 表示状态不是 READY 或已被占用）
     */
    int markProcessingIfReady(@Param("id") Long id);

    /**
     * 将影像状态从 PROCESSING 恢复为 READY。
     * 任务全部进入终态后释放影像，原始资产本身仍可继续被查询或再次处理。
     *
     * @param id 影像主键
     * @return 受影响行数
     */
    int markReadyIfProcessing(@Param("id") Long id);

    /**
     * 更新影像的可见性（PUBLIC / PRIVATE）。
     *
     * @param id         影像主键
     * @param ownerId    所有者用户 ID（用于权限校验）
     * @param visibility 目标可见性
     * @return 受影响行数
     */
    int updateVisibility(@Param("id") Long id,
                         @Param("ownerId") String ownerId,
                         @Param("visibility") String visibility);

    /**
     * 更新影像元数据 JSON。
     *
     * @param id 影像主键
     * @param ownerId 影像所有者 ID，用于限制只有 owner 可修改
     * @param metadataJson 新的 metadata_json 内容
     * @return 受影响行数
     */
    int updateMetadataJson(@Param("id") Long id,
                           @Param("ownerId") String ownerId,
                           @Param("metadataJson") String metadataJson);

    /**
     * 根据影像编码统计记录数，用于唯一性校验。
     *
     * @param imageCode 影像编码
     * @return 记录数
     */
    long countByImageCode(@Param("imageCode") String imageCode);

    /**
     * 回写缩略图对象键。
     * 缩略图生成发生在影像记录插入之后，因此单独回写该字段。
     *
     * @param id                影像主键
     * @param thumbnailObjectKey 缩略图对象键
     * @return 受影响行数
     */
    int updateThumbnailObjectKey(@Param("id") Long id, @Param("thumbnailObjectKey") String thumbnailObjectKey);

    /**
     * 更新缩略图生成状态。
     * 通过 fromStatus 做乐观锁，避免并发重复处理。
     *
     * @param id            影像主键
     * @param fromStatus    当前期望状态
     * @param toStatus      目标状态
     * @param errorMessage  失败时的错误信息
     * @return 受影响行数
     */
    int updateThumbnailStatus(@Param("id") Long id,
                              @Param("fromStatus") String fromStatus,
                              @Param("toStatus") String toStatus,
                              @Param("errorMessage") String errorMessage);
}
