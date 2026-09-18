package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.RiskLevel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证每一种告警类型都能生成符合最低工具数量要求的计划。 */
class ToolPlanningAgentTest {

    private final ToolPlanningAgent agent = new ToolPlanningAgent();

    /** 参数化测试会遍历全部 AlertType，新增枚举后也会自动纳入验证。 */
    @ParameterizedTest
    @EnumSource(AlertType.class)
    void shouldAlwaysPlanAtLeastThreeDifferentTools(AlertType alertType) {
        var recognition = new AlertRecognition(
                "payment-service",
                alertType,
                List.of(),
                RiskLevel.HIGH,
                true,
                false,
                "测试告警"
        );

        var plan = agent.plan(recognition);

        assertThat(plan.toolNames()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(plan.toolNames()).doesNotHaveDuplicates();
        assertThat(plan.toolNames()).contains("service-status", "error-log", "resource-usage");
    }
}
