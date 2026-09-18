package com.enterprise.opsassistant.domain;

import java.util.List;
import java.util.Objects;

/**
 * RootCauseAgent 综合全部工具证据后形成的根因评估。
 *
 * <p>根因分析不强迫系统只给出一个绝对结论，而是允许列出多个带置信度的候选原因，
 * 同时保留推理依据、最终风险、回滚与升级判断。这更符合真实运维场景中的不确定性。</p>
 *
 * @param finalRisk 结合告警文本、工具证据和安全规则确定的最终风险等级
 * @param candidates 按可信程度组织的根因候选列表
 * @param reasoning 从证据到结论的关键推理步骤，供人工复核
 * @param rollbackRecommended 是否建议回滚最近一次发布
 * @param escalationRequired 是否必须升级给人工或更高级别响应团队
 * @param userImpact 对最终用户影响范围和现象的描述
 */
public record RootCauseAssessment(
        RiskLevel finalRisk,
        List<RootCauseCandidate> candidates,
        List<String> reasoning,
        boolean rollbackRecommended,
        boolean escalationRequired,
        String userImpact) {

    /**
     * 校验最终风险并冻结候选和推理列表。
     * 未能确认用户影响时使用明确的“尚未确认”，避免空值在报告中被误解为“没有影响”。
     */
    public RootCauseAssessment {
        finalRisk = Objects.requireNonNull(finalRisk, "finalRisk must not be null");
        candidates = List.copyOf(Objects.requireNonNullElse(candidates, List.of()));
        reasoning = List.copyOf(Objects.requireNonNullElse(reasoning, List.of()));
        userImpact = Objects.requireNonNullElse(userImpact, "尚未确认").trim();
    }
}
