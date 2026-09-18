package com.enterprise.opsassistant.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ResponsePlanAgent 生成的结构化处置方案。
 *
 * <p>根因判断回答“为什么出故障”，处置方案回答“接下来由谁按什么顺序做什么”。二者分离后，
 * 即使未来更换模型或调整根因算法，最终动作仍必须通过本对象的顺序和完整性校验。</p>
 *
 * @param actions 按 order 从 1 开始连续排列的处置动作
 * @param followUpMetrics 处置过程中和处置后需要持续观察的指标
 * @param summary 对整套处置策略的简短说明
 */
public record ResponsePlan(
        List<RecommendedAction> actions,
        List<String> followUpMetrics,
        String summary) {

    /**
     * 验证处置方案能够被实际执行。
     *
     * <p>动作顺序必须从 1 连续递增，防止前端排序或人工执行时出现缺号、重复号；观察指标至少
     * 有一项，防止系统只给出操作却没有验证故障是否恢复的方法。</p>
     */
    public ResponsePlan {
        actions = List.copyOf(Objects.requireNonNullElse(actions, List.of()));
        followUpMetrics = List.copyOf(Objects.requireNonNullElse(followUpMetrics, List.of()));
        summary = requireText(summary, "summary");

        if (actions.isEmpty()) {
            throw new IllegalArgumentException("response plan must contain at least one action");
        }
        for (int index = 0; index < actions.size(); index++) {
            if (actions.get(index).order() != index + 1) {
                throw new IllegalArgumentException("action order must start at 1 and remain continuous");
            }
        }
        if (followUpMetrics.isEmpty()) {
            throw new IllegalArgumentException("response plan must contain follow-up metrics");
        }
        if (followUpMetrics.stream().anyMatch(metric -> metric == null || metric.isBlank())) {
            throw new IllegalArgumentException("followUpMetrics must not contain blank values");
        }
        Set<String> uniqueMetrics = new HashSet<>(followUpMetrics);
        if (uniqueMetrics.size() != followUpMetrics.size()) {
            throw new IllegalArgumentException("followUpMetrics must not contain duplicates");
        }
    }

    /** 校验方案摘要等必填文本，并清理首尾空白。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
