package com.remotesensing.platform.service;

import com.remotesensing.platform.dto.RemoteSensingTaskMessage;

/**
 * 发件箱消息服务接口。
 * <p>
 * 发件箱模式（Transactional Outbox Pattern）实现，
 * 确保数据库事务与消息发送的最终一致性。
 * 消息先写入发件箱表，再由定时任务轮询发送到 RabbitMQ。
 */
public interface MessageOutboxService {

    /**
     * 创建任务消息发件箱记录（在同一事务中）。
     *
     * @param taskId  任务 ID
     * @param message 遥处理任务消息体
     * @return 发件箱记录 ID
     */
    Long createTaskMessage(Long taskId, RemoteSensingTaskMessage message);

    /**
     * 根据 ID 发布单条发件箱消息到 RabbitMQ。
     *
     * @param outboxId 发件箱记录 ID
     */
    void publishById(Long outboxId);

    /**
     * 轮询所有到期待发送的消息并发布。
     * 由定时任务（@Scheduled）周期调用。
     */
    void publishDueMessages();
}
