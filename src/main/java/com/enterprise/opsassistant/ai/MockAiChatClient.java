package com.enterprise.opsassistant.ai;

/**
 * 无需网络和 API Key 的确定性 Mock 模型客户端。
 *
 * <p>本地开发和自动化测试默认使用它，保证项目开箱可启动。Mock 的存在不冒充真实模型：响应和
 * 最终报告都会记录 mock provider 与模型名。</p>
 */
public class MockAiChatClient implements AiChatClient {

    private final String providerName;
    private final String modelName;

    public MockAiChatClient(String providerName, String modelName) {
        this.providerName = normalize(providerName, "mock");
        this.modelName = normalize(modelName, "mock-ops-assistant");
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public String modelName() {
        return modelName;
    }

    /** 返回固定但符合运维安全边界的复核文本，便于后续集成 AI Service。 */
    @Override
    public AiChatResponse chat(AiChatRequest request) {
        long startedAt = System.nanoTime();
        String content = "AI Mock 复核完成：已结合告警和工具证据进行检查；"
                + "Java 安全规则结论应作为风险下限，所有生产变更必须人工确认。";
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new AiChatResponse(content, providerName, modelName, latencyMs);
    }

    private String normalize(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
