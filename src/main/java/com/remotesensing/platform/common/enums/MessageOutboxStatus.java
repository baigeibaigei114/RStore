package com.remotesensing.platform.common.enums;

/**
 * 发件箱消息状态枚举，对应 message_outbox.status 字段。
 * <p>
 * 发件箱模式（Outbox Pattern）用于确保消息可靠投递：
 * 先在本库插入消息记录，再由定时任务轮询发送到 RabbitMQ。
 * <p>
 * 状态流转：PENDING -> SENDING -> SENT (终态)
 *                               -> FAILED (终态，可重试)
 */
public enum MessageOutboxStatus {

    /** 消息待发送，等待定时任务轮询。 */
    PENDING,

    /** 消息正在发送中。 */
    SENDING,

    /** 消息已成功发送到 RabbitMQ。 */
    SENT,

    /** 消息发送失败，等待后续重试。 */
    FAILED;

    /** 返回数据库持久化时使用的字符串值（枚举名）。 */
    public String dbValue() {
        return name();
    }
}
