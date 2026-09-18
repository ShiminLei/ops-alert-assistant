package com.enterprise.opsassistant.ai;

import java.util.List;

/**
 * 交给 AI 客户端的厂商无关请求。
 *
 * <p>温度、Token 上限和超时属于每个 Provider 的配置，由客户端读取；业务层只描述任务和消息。</p>
 *
 * @param businessId 本次业务分析编号，用于日志和指标关联
 * @param promptName 提示词用途名称，例如 ops-incident-review
 * @param messages 按对话顺序排列的消息
 */
public record AiChatRequest(String businessId, String promptName, List<AiMessage> messages) {

    /** 冻结消息列表并验证调用审计字段。 */
    public AiChatRequest {
        if (businessId == null || businessId.isBlank()) {
            throw new IllegalArgumentException("businessId must not be blank");
        }
        if (promptName == null || promptName.isBlank()) {
            throw new IllegalArgumentException("promptName must not be blank");
        }
        messages = List.copyOf(messages == null ? List.of() : messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
    }
}
