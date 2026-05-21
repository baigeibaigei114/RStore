package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsTask;
import com.remotesensing.platform.vo.RsTaskListVO;
import com.remotesensing.platform.vo.RsTaskVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 遥感任务（rs_task）Mapper 接口。
 * 提供任务的增删改查、状态流转、Worker 领取、重试标记等操作。
 */
@Mapper
public interface RsTaskMapper {

    /**
     * 插入一条新的 PENDING 状态任务记录。
     * 先插入拿到自增 taskId，再基于 taskId 生成输出文件路径后回写。
     *
     * @param task 任务实体，插入后 MyBatis 回填 id 字段
     * @return 受影响行数
     */
    int insert(RsTask task);

    /**
     * 根据主键 ID 查询任务。
     *
     * @param id 任务主键
     * @return 任务实体，不存在返回 null
     */
    RsTask selectById(@Param("id") Long id);

    /**
     * 根据主键 ID 和所有者 ID 查询任务，用于权限校验。
     *
     * @param id      任务主键
     * @param ownerId 所有者用户 ID
     * @return 任务实体，不匹配所有者时返回 null
     */
    RsTask selectByIdForOwner(@Param("id") Long id,
                              @Param("ownerId") String ownerId);

    /**
     * 查询任务详情 VO，包含关联影像名称等扩展信息。
     *
     * @param id 任务主键
     * @return 任务详情 VO，包含 imageName 等 JOIN 字段
     */
    RsTaskVO selectDetailById(@Param("id") Long id);

    /**
     * 查询当前用户的任务详情 VO，带所有者权限过滤。
     *
     * @param id      任务主键
     * @param ownerId 所有者用户 ID
     * @return 任务详情 VO，不匹配所有者时返回 null
     */
    RsTaskVO selectDetailByIdForOwner(@Param("id") Long id,
                                      @Param("ownerId") String ownerId);

    /**
     * 分页查询当前用户的任务列表（摘要信息）。
     *
     * @param offset   分页偏移量
     * @param pageSize 每页数量
     * @param ownerId  所有者用户 ID
     * @return 任务列表 VO 集合
     */
    List<RsTaskListVO> selectPage(@Param("offset") int offset,
                                  @Param("pageSize") int pageSize,
                                  @Param("ownerId") String ownerId);

    /**
     * 统计当前用户的任务总数。
     *
     * @param ownerId 所有者用户 ID
     * @return 任务总数
     */
    long count(@Param("ownerId") String ownerId);

    /**
     * 更新任务的输出存储路径。
     * 输出路径依赖 taskId 生成，因此作为任务创建后的独立回写步骤。
     *
     * @param id              任务主键
     * @param outputBucket    输出 MinIO 存储桶
     * @param outputObjectKey 输出对象键
     * @return 受影响行数
     */
    int updateOutputObject(@Param("id") Long id,
                           @Param("outputBucket") String outputBucket,
                           @Param("outputObjectKey") String outputObjectKey);

    /**
     * 更新任务状态和错误信息。
     * 用于消息发送或后续处理失败时的状态回写。
     *
     * @param id           任务主键
     * @param status       目标状态值
     * @param errorMessage 错误信息（可为空）
     * @return 受影响行数
     */
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage);

    /**
     * Worker 节点回调更新任务状态。
     * 通过 currentStatus 做乐观锁，避免并发覆盖已变更的状态。
     * 支持同时更新进度、输出路径和错误信息。
     *
     * @param id              任务主键
     * @param currentStatus   当前期望状态（乐观锁条件）
     * @param status          目标状态值
     * @param progress        进度百分比
     * @param outputObjectKey 输出对象键
     * @param errorMessage    错误信息
     * @return 受影响行数
     */
    int updateStatusFromWorker(@Param("id") Long id,
                               @Param("currentStatus") String currentStatus,
                               @Param("status") String status,
                               @Param("progress") Integer progress,
                               @Param("outputObjectKey") String outputObjectKey,
                               @Param("errorMessage") String errorMessage);

    /**
     * Worker 领取任务：将 PENDING 状态的任务更新为 PROCESSING。
     * 通过 WHERE status='PENDING' 条件保证并发安全，只有一个 Worker 能领取成功。
     *
     * @param id 任务主键
     * @return 受影响行数（0 表示已被其他 Worker 领取）
     */
    int claimForRunning(@Param("id") Long id);

    /**
     * 统计指定影像下处于活跃状态（非终态）的任务数量。
     * 删除影像前显式检查，避免只依赖外键异常表达业务状态。
     *
     * @param imageId 影像主键
     * @return 活跃任务数
     */
    long countActiveByImageId(@Param("imageId") Long imageId);

    /**
     * 将活跃（非终态）任务标记为最终失败。
     * 用于消息投递失败或进入死信队列后的状态兜底。
     *
     * @param id           任务主键
     * @param errorMessage 失败原因描述
     * @return 受影响行数
     */
    int markFailedIfActive(@Param("id") Long id,
                           @Param("errorMessage") String errorMessage);
}