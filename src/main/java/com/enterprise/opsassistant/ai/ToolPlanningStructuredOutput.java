package com.enterprise.opsassistant.ai;

import java.util.List;
import java.util.Objects;

/**
 * Spring AI 为一次告警提出的结构化工具计划候选。
 *
 * <p>该对象只表达模型建议，不代表工具已经获准执行。ToolPlanningAgent 必须使用
 * OperationsToolCatalog 过滤白名单、去重并补齐基础工具后，才能创建最终 ToolPlan。</p>
 *
 * @param toolNames 按建议调用顺序排列的注册工具名称
 * @param rationale 模型说明这些工具与当前告警之间的关系
 */
public record ToolPlanningStructuredOutput(
        List<String> toolNames,
        String rationale) {

    /** 归一化模型可能省略的列表和说明，后续业务校验仍由 Agent 执行。 */
    public ToolPlanningStructuredOutput {
        toolNames = List.copyOf(Objects.requireNonNullElse(toolNames, List.of()));
        rationale = rationale == null || rationale.isBlank()
                ? "AI 未提供工具选择理由"
                : rationale.trim();
    }
}
