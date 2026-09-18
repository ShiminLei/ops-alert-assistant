package com.enterprise.opsassistant.ai;

/**
 * 单个模型客户端返回的标准响应。
 *
 * @param content 模型生成文本
 * @param provider 实际提供方
 * @param model 实际模型名称
 * @param latencyMs 调用耗时，单位毫秒
 */
public record AiChatResponse(String content, String provider, String model, long latencyMs) {

    /** 确保成功响应具备可审计的来源，并禁止负耗时。 */
    public AiChatResponse {
        content = requireText(content, "content");
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
