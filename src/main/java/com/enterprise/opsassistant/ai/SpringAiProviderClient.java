package com.enterprise.opsassistant.ai;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 一个可参与主备路由的 Spring AI Provider 客户端。
 *
 * <p>{@code providerName} 是业务配置中的稳定名称，例如 deepseek、bailian；{@code modelName}
 * 是厂商模型名，例如 deepseek-chat、qwen-plus；{@code chatClient} 则是真正执行提示词、
 * Structured Output、Tool Calling、Advisor 和可观测链路的 Spring AI 客户端。</p>
 *
 * <p>将来源信息与 ChatClient 绑定，可以在发生主备切换后准确写入最终报告和指标，避免只知道
 * “调用成功”却不知道究竟由哪个模型完成。</p>
 */
public record SpringAiProviderClient(
        String providerName,
        String modelName,
        ChatClient chatClient) {

    public SpringAiProviderClient {
        providerName = requireText(providerName, "providerName");
        modelName = requireText(modelName, "modelName");
        if (chatClient == null) {
            throw new IllegalArgumentException("chatClient must not be null");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
