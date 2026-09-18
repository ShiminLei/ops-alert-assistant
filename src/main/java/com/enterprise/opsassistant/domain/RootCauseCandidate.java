package com.enterprise.opsassistant.domain;

import java.util.List;
import java.util.Objects;

/**
 * 一个可能的故障根因及其证据关联。
 *
 * <p>候选原因必须引用 evidenceId，而不是只依赖模型自由发挥。最终报告因此可以追溯
 * “为什么认为这是根因”，也方便前端把结论与工具返回的数据对应展示。</p>
 *
 * @param description 对候选根因的清晰描述
 * @param confidence 置信度，范围为 0 到 1；它表示证据支持程度，不等同于统计概率
 * @param evidenceIds 支持这个候选原因的工具证据编号
 */
public record RootCauseCandidate(
        String description,
        double confidence,
        List<String> evidenceIds) {

    /** 校验描述与置信度范围，并冻结证据编号列表，保证分析结果创建后不可变。 */
    public RootCauseCandidate {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        description = description.trim();
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        evidenceIds = List.copyOf(Objects.requireNonNullElse(evidenceIds, List.of()));
    }
}
