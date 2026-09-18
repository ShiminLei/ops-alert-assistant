package com.enterprise.opsassistant.ai;

/**
 * AI 对规则分析结果进行复核后的标准输出。
 *
 * @param content 模型补充分析；模型不可用时为规则兜底说明
 * @param provider 实际成功提供方，全部模型失败时为 rule-engine
 * @param model 实际模型名，全部模型失败时为确定性安全模型
 * @param fallbackUsed 是否尝试并使用过降级路径
 * @param ruleOnly 是否因为模型全部不可用而只保留 Java 规则结果
 */
public record AiReviewResult(
        String content,
        String provider,
        String model,
        boolean fallbackUsed,
        boolean ruleOnly) {

    /** 确保最终报告始终能说明分析内容和来源。 */
    public AiReviewResult {
        content = requireText(content, "content");
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
    }

    /** 创建完全不调用模型的规则结果，主要用于纯单元测试或显式禁用 AI 的场景。 */
    public static AiReviewResult ruleOnly(String reason, boolean fallbackAttempted) {
        return new AiReviewResult(
                reason,
                "rule-engine",
                "deterministic-safety-model-v1",
                fallbackAttempted,
                true
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
