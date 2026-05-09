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

    /**
     * 将任务标记为最终失败，用于消息投递失败或进入死信队列后的状态兜底。
     */
    int markFailed(@Param("id") Long id,
                   @Param("errorMessage") String errorMessage);
}
