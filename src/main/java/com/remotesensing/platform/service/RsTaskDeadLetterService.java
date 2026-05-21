package com.remotesensing.platform.service;

import com.remotesensing.platform.dto.RemoteSensingTaskMessage;
import org.springframework.amqp.core.Message;

/**
 * 死信记录服务接口。
 * <p>
 * 当任务消息超过重试次数进入死信队列时，由监听器调用该服务
 * 将死信信息（包括原始消息内容和 AMQP 属性）持久化到数据库，
 * 以便人工排查和后续补偿处理。
 */
public interface RsTaskDeadLetterService {

    /**
     * 记录死信消息到数据库。
     *
     * @param taskMessage 远程感任务消息体
     * @param rawMessage  AMQP 原始消息（含路由信息、头属性等）
     */
    void record(RemoteSensingTaskMessage taskMessage, Message rawMessage);
}
