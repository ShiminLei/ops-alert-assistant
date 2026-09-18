package com.enterprise.opsassistant.domain;

import java.util.List;
import java.util.Objects;

/**
 * EvidenceCollectorAgent 完成一轮工具调用后的汇总结果。
 *
 * <p>除原始证据列表外，额外保存成功、部分成功和失败数量，方便编排层快速决定是否可以继续
 * 根因分析，前端也可以直接展示本轮数据完整度。</p>
 *
 * @param plan 实际执行的工具计划
 * @param evidence 按计划顺序返回的工具证据，每个计划项都应对应一条证据
 * @param successCount 完整成功的工具数量
 * @param partialCount 只得到部分数据的工具数量
 * @param failureCount 调用失败或找不到工具的数量
 */
public record EvidenceCollectionResult(
        ToolPlan plan,
        List<ToolEvidence> evidence,
        long successCount,
        long partialCount,
        long failureCount) {

    /** 验证汇总数字与证据列表保持一致，防止报告出现互相矛盾的统计。 */
    public EvidenceCollectionResult {
        plan = Objects.requireNonNull(plan, "plan must not be null");
        evidence = List.copyOf(Objects.requireNonNullElse(evidence, List.of()));
        if (evidence.size() != plan.toolNames().size()) {
            throw new IllegalArgumentException("each planned tool must produce exactly one evidence item");
        }
        if (successCount < 0 || partialCount < 0 || failureCount < 0) {
            throw new IllegalArgumentException("evidence counters must not be negative");
        }
        if (successCount + partialCount + failureCount != evidence.size()) {
            throw new IllegalArgumentException("evidence counters must match evidence size");
        }
    }

    /**
     * 判断证据是否达到进入根因分析的最低要求。
     * 完整成功和部分成功都说明取得了数据，但至少需要三条可用证据进行交叉验证。
     */
    public boolean hasEnoughEvidence() {
        return successCount + partialCount >= 3;
    }
}
