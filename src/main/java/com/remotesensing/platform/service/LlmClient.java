package com.remotesensing.platform.service;

import com.remotesensing.platform.dto.LlmMessage;
import java.util.List;

/**
 * LLM 客户端抽象，业务层只依赖该接口，不绑定具体模型厂商。
 */
public interface LlmClient {

    /**
     * 调用模型并要求返回 JSON 字符串。
     *
     * @param messages Chat Completions 消息列表
     * @return 模型返回的 JSON 字符串
     */
    String chatJson(List<LlmMessage> messages);
}
