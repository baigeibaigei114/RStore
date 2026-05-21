package com.remotesensing.platform.entity;

import java.time.OffsetDateTime;
import lombok.Data;

/**
 * 发件箱消息实体，对应 message_outbox 表。
 * <p>
 * 发件箱模式（Transactional Outbox Pattern）实现：
 * 在业务事务中同时写入数据库记录和发件箱消息，
 * 再由后台定时任务轮询发件箱，将消息可靠地投递到 RabbitMQ。
 * 这种方式保证了业务操作和消息发送的最终一致性。
 */
@Data
public class MessageOutbox {

    /** 主键 ID。 */
    private Long id;

    /** 聚合类型（业务类型标识，如 "rs_task"）。 */
    private String aggregateType;

    /** 聚合根 ID（关联的业务记录主键）。 */
    private Long aggregateId;

    /** 目标 RabbitMQ 交换器名称。 */
    private String exchangeName;

    /** 目标路由键。 */
    private String routingKey;

    /** 消息体 JSON。 */
    private String payload;

    /** 消息状态：PENDING / SENDING / SENT / FAILED。 */
    private String status;

    /** 当前重试次数。 */
    private Integer retryCount;

    /** 最大重试次数。 */
    private Integer maxRetryCount;

    /** 下次重试时间（用于退避策略）。 */
    private OffsetDateTime nextRetryAt;

    /** 消息发送成功时间。 */
    private OffsetDateTime sentAt;

    /** 发送失败时的错误信息。 */
    private String errorMessage;

    /** 创建时间。 */
    private OffsetDateTime createdAt;

    /** 最后更新时间。 */
    private OffsetDateTime updatedAt;
}
