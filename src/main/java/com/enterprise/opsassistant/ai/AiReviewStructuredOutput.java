package com.enterprise.opsassistant.ai;

import java.util.List;
import java.util.Objects;

/**
 * Spring AI 将模型输出转换成的结构化事故复核结果。
 *
 * <p>过去的实现要求模型返回一整段自由文本，程序只能把这段文本原样展示，无法可靠地区分
 * “证据是否一致”“最可能根因”“证据缺口”和“处置注意事项”。现在
 * {@code ChatClient.call().entity(AiReviewStructuredOutput.class)} 会把期望的 JSON 结构说明
 * 自动加入提示词，并将模型响应反序列化成这个 record。</p>
 *
 * <p>结构化输出并不意味着完全相信模型。这个对象只承载 AI 的分析意见，最终风险等级、是否回滚
 * 和允许执行的动作仍由 Java 安全规则决定。这样既能利用模型的语义推理能力，也不会让模型绕过
 * 确定性的运维安全边界。</p>
 *
 * @param evidenceConsistency 工具证据之间是否相互印证，存在冲突时应明确指出冲突
 * @param mostLikelyRootCause 模型依据现有证据判断的最可能根因
 * @param evidenceGaps 当前仍然缺少、需要人工或后续工具补充的证据
 * @param handlingNotes 执行处置建议时需要特别注意的顺序、验证点和安全事项
 */
public record AiReviewStructuredOutput(
        String evidenceConsistency,
        String mostLikelyRootCause,
        List<String> evidenceGaps,
        List<String> handlingNotes) {

    /**
     * 对模型输出做最基本的空值归一化。
     *
     * <p>大模型偶尔会省略没有内容的数组或文本字段。这里把 null 变成明确说明或空列表，防止
     * 后续生成报告时出现空指针，同时也避免把“模型没有回答”误表示成“已经确认没有问题”。</p>
     */
    public AiReviewStructuredOutput {
        evidenceConsistency = normalizeText(evidenceConsistency, "模型未说明证据一致性");
        mostLikelyRootCause = normalizeText(mostLikelyRootCause, "模型未给出额外根因判断");
        evidenceGaps = List.copyOf(Objects.requireNonNullElse(evidenceGaps, List.of()));
        handlingNotes = List.copyOf(Objects.requireNonNullElse(handlingNotes, List.of()));
    }

    /**
     * 将结构化字段转换成人员容易阅读的简短文字，兼容当前报告中的 reasoning 字段。
     * 后续前端升级后可以直接分别展示四个字段，不再需要这层文本拼装。
     */
    public String toNarrative() {
        return "证据一致性：" + evidenceConsistency
                + "；最可能根因：" + mostLikelyRootCause
                + "；证据缺口：" + joinOrDefault(evidenceGaps, "暂无新增缺口")
                + "；处置注意事项：" + joinOrDefault(handlingNotes, "遵循 Java 安全规则并由人工确认");
    }

    private static String normalizeText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String joinOrDefault(List<String> values, String defaultValue) {
        List<String> normalized = values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
        return normalized.isEmpty() ? defaultValue : String.join("、", normalized);
    }
}
