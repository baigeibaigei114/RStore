package com.remotesensing.platform.service;

import com.remotesensing.platform.vo.AiQueryIntentVO;

/**
 * 自然语言影像检索解析服务。
 */
public interface AiQueryParseService {

    /**
     * 将自然语言解析为结构化影像检索条件。
     */
    AiQueryIntentVO parse(String text);
}
