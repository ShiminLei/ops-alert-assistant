package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.ai.ToolPlanningAiService;
import com.enterprise.opsassistant.ai.ToolPlanningStructuredOutput;
import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.RiskLevel;
import com.enterprise.opsassistant.tool.OperationsTool;
import com.enterprise.opsassistant.tool.OperationsToolCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /** AI 可以动态增加专业工具，但未注册工具和重复项必须被 Java 删除。 */
    @Test
    void shouldAcceptOnlyRegisteredAiToolSuggestions() {
        ToolPlanningAiService aiService = mock(ToolPlanningAiService.class);
        OperationsToolCatalog catalog = catalog();
        AlertRecognition recognition = recognition(AlertType.DATABASE_CONNECTION);
        when(aiService.plan("conversation-tool-plan", recognition))
                .thenReturn(Optional.of(new ToolPlanningStructuredOutput(
                        List.of("database-connection", "delete-production", "database-connection"),
                        "检查数据库连接状态"
                )));

        ToolPlanningAgent aiAgent = new ToolPlanningAgent(aiService, catalog);
        var plan = aiAgent.plan(recognition, "conversation-tool-plan");

        assertThat(plan.toolNames()).containsExactly(
                "service-status", "error-log", "resource-usage", "database-connection");
        assertThat(plan.toolNames()).doesNotContain("delete-production");
        assertThat(plan.rationale()).contains("白名单校验", "三项基础调查");
    }

    /** 模型不可用时必须恢复原规则中的专项工具，而不是只剩三个基础查询。 */
    @Test
    void shouldUseCompleteRulePlanWhenAiIsUnavailable() {
        ToolPlanningAiService aiService = mock(ToolPlanningAiService.class);
        AlertRecognition recognition = recognition(AlertType.POST_DEPLOYMENT_FAILURE);
        when(aiService.plan("conversation-tool-fallback", recognition))
                .thenReturn(Optional.empty());

        ToolPlanningAgent aiAgent = new ToolPlanningAgent(aiService, catalog());
        var plan = aiAgent.plan(recognition, "conversation-tool-fallback");

        assertThat(plan.toolNames()).contains(
                "deployment", "database-connection", "dependency-status");
    }

    private AlertRecognition recognition(AlertType alertType) {
        return new AlertRecognition(
                "payment-service", alertType, List.of(), RiskLevel.HIGH,
                true, true, "测试告警");
    }

    private OperationsToolCatalog catalog() {
        return new OperationsToolCatalog(List.of(
                tool("service-status"),
                tool("error-log"),
                tool("resource-usage"),
                tool("deployment"),
                tool("dependency-status"),
                tool("database-connection")
        ));
    }

    private OperationsTool tool(String name) {
        OperationsTool tool = mock(OperationsTool.class);
        when(tool.name()).thenReturn(name);
        return tool;
    }
}
