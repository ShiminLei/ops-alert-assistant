package com.enterprise.opsassistant.domain;

import java.util.Objects;

/**
 * 报告中的一条可执行处置建议。
 *
 * <p>建议不仅包含自然语言动作，还明确执行次序、紧急程度和责任角色，避免 AI 只输出一段
 * 看似合理但无法落地的泛化文字。</p>
 *
 * @param order 从 1 开始的执行顺序；数值越小越优先
 * @param urgency 动作的紧急程度
 * @param action 具体且可执行的操作说明
 * @param owner 建议执行该动作的角色，例如应用值班、DBA 或平台运维
 */
public record RecommendedAction(
        int order,
        ActionUrgency urgency,
        String action,
        String owner) {

    /** 保证动作能够被可靠排序，并且执行内容和责任人均不为空。 */
    public RecommendedAction {
        if (order < 1) {
            throw new IllegalArgumentException("order must be greater than zero");
        }
        urgency = Objects.requireNonNull(urgency, "urgency must not be null");
        action = requireText(action, "action");
        owner = requireText(owner, "owner");
    }

    /** 校验动作和负责人等必填文本，并统一清理首尾空白。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
