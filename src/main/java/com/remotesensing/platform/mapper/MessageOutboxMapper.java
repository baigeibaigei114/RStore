package com.remotesensing.platform.mapper;

import com.remotesensing.platform.entity.MessageOutbox;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 消息发件箱（message_outbox）Mapper 接口。
 * 实现发件箱模式（Outbox Pattern），确保任务消息可靠投递到 RabbitMQ。
 * 消息先写入数据库，再由定时任务轮询发送并更新状态。
 */
@Mapper
public interface MessageOutboxMapper {

    /**
     * 插入一条发件箱记录。
     *
     * @param outbox 发件箱实体，插入后 MyBatis 回填 id
     * @return 受影响行数
     */
    int insert(MessageOutbox outbox);

    /**
     * 根据主键 ID 查询发件箱记录。
     *
     * @param id 发件箱主键
     * @return 发件箱实体
     */
    MessageOutbox selectById(@Param("id") Long id);

    /**
     * 根据任务 ID 查询对应的发件箱记录。
     *
     * @param taskId 任务主键
     * @return 发件箱实体
     */
    MessageOutbox selectByTaskId(@Param("taskId") Long taskId);

    /**
     * 查询所有到期待发送的消息（当前时间 >= next_retry_at 且状态为 PENDING 或 FAILED）。
     *
     * @param now   当前时间
     * @param limit 最大返回数量
     * @return 待发送的消息集合
     */
    List<MessageOutbox> selectDueMessages(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /**
     * 标记一次发布尝试：更新 last_attempt_at 和 next_retry_at。
     * 用于记录投递时间并设置下次重试时间。
     *
     * @param id           发件箱主键
     * @param now          当前时间
     * @param nextRetryAt  下次重试时间
     * @return 受影响行数
     */
    int markPublishAttempt(@Param("id") Long id,
                           @Param("now") OffsetDateTime now,
                           @Param("nextRetryAt") OffsetDateTime nextRetryAt);

    /**
     * 将状态为 SENDING 的消息标记为已发送（SENT）。
     *
     * @param id 发件箱主键
     * @return 受影响行数
     */
    int markSentIfSending(@Param("id") Long id);

    /**
     * 将非失败状态的消息直接标记为已发送（SENT）。
     * 用于幂等性处理，已失败的消息不会被误标记。
     *
     * @param id 发件箱主键
     * @return 受影响行数
     */
    int markSentIfNotFailed(@Param("id") Long id);

    /**
     * 将状态为 SENDING 的消息标记为发送失败（FAILED），并记录错误和下次重试时间。
     *
     * @param id            发件箱主键
     * @param errorMessage  失败错误信息
     * @param nextRetryAt   下次重试时间
     * @return 受影响行数
     */
    int markFailedIfSending(@Param("id") Long id,
                            @Param("errorMessage") String errorMessage,
                            @Param("nextRetryAt") OffsetDateTime nextRetryAt);
}