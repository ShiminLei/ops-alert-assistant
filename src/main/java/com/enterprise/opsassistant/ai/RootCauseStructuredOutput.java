package com.enterprise.opsassistant.ai;

import java.util.List;
import java.util.Objects;

/**
 * Spring AI 在根因综合阶段返回的结构化候选结果。
 *
 * <p>这个对象仍然属于“模型建议”，不能直接成为最终事故结论。RootCauseAgent 会验证每个候选
 * 引用的 evidenceId 是否来自本轮真实工具结果，并继续使用 Java 规则确定风险、回滚和升级边界。</p>
 *
 * @param candidates 模型根据工具证据提出的根因候选
 * @param reasoning 模型说明如何从证据得到候选结论的简短推理步骤
 */
public record RootCauseStructuredOutput(
        List<Candidate> candidates,
        List<String> reasoning) {

    /** 将模型可能省略的数组归一化为空数组，避免业务层处理 null。 */
    public RootCauseStructuredOutput {
        candidates = List.copyOf(Objects.requireNonNullElse(candidates, List.of()));
        reasoning = List.copyOf(Objects.requireNonNullElse(reasoning, List.of()));
    }

    /**
     * 模型提出的单个根因候选。
     *
     * <p>这里故意不校验 evidenceIds 和 confidence，因为它们来自不可信的模型输出。真正的
     * 白名单校验、置信度范围校验和证据数量上限由 RootCauseAgent 集中执行。</p>
     *
     * @param description 根因描述
     * @param confidence 模型给出的证据支持程度，期望范围为 0 到 1
     * @param evidenceIds 模型声称可以支持该结论的证据编号
     */
    public record Candidate(
            String description,
            Double confidence,
            List<String> evidenceIds) {

        /** 只做 null 归一化，不在模型边界对象中假装数据已经可信。 */
        public Candidate {
            evidenceIds = List.copyOf(Objects.requireNonNullElse(evidenceIds, List.of()));
        }
    }
}
