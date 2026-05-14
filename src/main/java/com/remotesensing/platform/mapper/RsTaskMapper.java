package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.RsTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RsTaskMapper {

    /**
     * 先插入 PENDING 任务拿到 taskId，再基于 taskId 生成结果文件路径。
     */
    int insert(RsTask task);

    RsTask selectById(@Param("id") Long id);

    /**
     * 输出路径依赖 taskId，因此作为任务创建后的独立回写步骤。
     */
    int updateOutputObject(@Param("id") Long id,
                           @Param("outputBucket") String outputBucket,
                           @Param("outputObjectKey") String outputObjectKey);

    /**
     * 消息发送或后续处理失败时更新任务状态和错误摘要。
     */
    int updateStatus(@Param("id") Long id,
                     @Param("status") String status,
                     @Param("errorMessage") String errorMessage);

    int updateStatusFromWorker(@Param("id") Long id,
                               @Param("currentStatus") String currentStatus,
                               @Param("status") String status,
                               @Param("progress") Integer progress,
                               @Param("outputObjectKey") String outputObjectKey,
                               @Param("errorMessage") String errorMessage);

    int claimForRunning(@Param("id") Long id);

    /**
     * 删除影像前显式检查未完成任务，避免只依赖外键异常表达业务状态。
     */
    long countActiveByImageId(@Param("imageId") Long imageId);

    /**
     * 将任务标记为最终失败，用于消息投递失败或进入死信队列后的状态兜底。
     */
    int markFailedIfActive(@Param("id") Long id,
                           @Param("errorMessage") String errorMessage);
}
