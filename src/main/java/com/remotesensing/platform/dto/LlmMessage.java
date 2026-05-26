package com.remotesensing.platform.dto;

/**
 * OpenAI-compatible Chat Completions 消息。
 *
 * @param role    system / user / assistant
 * @param content 消息内容
 */
public record LlmMessage(String role, String content) {
}
