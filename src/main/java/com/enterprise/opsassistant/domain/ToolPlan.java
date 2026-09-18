package com.enterprise.opsassistant.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 一次告警分析需要执行的运维工具计划。
 *
 * <p>工具规划与工具执行被拆成两个步骤：先得到可检查的计划，再逐项调用工具。这样既便于
 * SupervisorAgent 输出“准备查询哪些系统”的流式进度，也便于日志审计和单元测试。</p>
 *
 * @param serviceName 要调查的服务名
 * @param alertType 触发该计划的标准告警类型
 * @param toolNames 按执行顺序排列的工具名；作业要求每次分析至少调用三个 Mock 工具
 * @param rationale 为什么选择这些工具的简短说明
 */
public record ToolPlan(
        String serviceName,
        AlertType alertType,
        List<String> toolNames,
        String rationale) {

    /**
     * 校验工具计划的最低质量要求。
     *
     * <p>{@link LinkedHashSet} 同时完成去重和保持原顺序。去重后不足三个工具时立即拒绝计划，
     * 将作业要求变成代码约束，而不是只依赖提示词或开发者记忆。</p>
     */
    public ToolPlan {
        serviceName = requireText(serviceName, "serviceName");
        alertType = Objects.requireNonNullElse(alertType, AlertType.UNKNOWN);
        toolNames = List.copyOf(new LinkedHashSet<>(Objects.requireNonNullElse(toolNames, List.of())));
        if (toolNames.size() < 3) {
            throw new IllegalArgumentException("a tool plan must contain at least three different tools");
        }
        if (toolNames.stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("toolNames must not contain blank values");
        }
        rationale = requireText(rationale, "rationale");
    }

    /** 校验必填文本，并去掉首尾空白。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
