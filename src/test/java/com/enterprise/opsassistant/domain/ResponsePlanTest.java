package com.enterprise.opsassistant.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证处置计划的顺序约束不会被调用方绕过。 */
class ResponsePlanTest {

    /** 动作编号缺少 1 时应立即拒绝，防止生成无法可靠执行的报告。 */
    @Test
    void shouldRejectNonContinuousActionOrder() {
        var invalidAction = new RecommendedAction(
                2,
                ActionUrgency.OBSERVATION,
                "继续观察",
                "应用值班"
        );

        assertThatThrownBy(() -> new ResponsePlan(
                List.of(invalidAction),
                List.of("服务可用率"),
                "测试方案"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order");
    }
}
