package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.domain.ActionUrgency;
import com.enterprise.opsassistant.mock.MockOperationsDataStore;
import com.enterprise.opsassistant.tool.DatabaseConnectionTool;
import com.enterprise.opsassistant.tool.DeploymentTool;
import com.enterprise.opsassistant.tool.DependencyStatusTool;
import com.enterprise.opsassistant.tool.ErrorLogTool;
import com.enterprise.opsassistant.tool.OperationsTool;
import com.enterprise.opsassistant.tool.ResourceUsageTool;
import com.enterprise.opsassistant.tool.ServiceStatusTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用完整前置链路验证处置动作是否与真实 Mock 证据一致。 */
class ResponsePlanAgentTest {

    private final AlertParserAgent parser = new AlertParserAgent();
    private final ToolPlanningAgent planner = new ToolPlanningAgent();
    private final RootCauseAgent rootCauseAgent = new RootCauseAgent();
    private final ResponsePlanAgent responsePlanAgent = new ResponsePlanAgent();
    private EvidenceCollectorAgent collector;

    @BeforeEach
    void setUp() {
        var dataStore = new MockOperationsDataStore();
        List<OperationsTool> tools = List.of(
                new ServiceStatusTool(dataStore),
                new ErrorLogTool(dataStore),
                new DeploymentTool(dataStore),
                new ResourceUsageTool(dataStore),
                new DependencyStatusTool(dataStore),
                new DatabaseConnectionTool(dataStore)
        );
        collector = new EvidenceCollectorAgent(tools);
    }

    /** 支付事故方案必须包含升级、准确版本回滚、连接池处理及对应观察指标。 */
    @Test
    void shouldCreateExecutablePlanForPaymentIncident() {
        var recognition = parser.parse(
                "支付服务刚发布后大量请求超时，错误率18.7%，用户支付失败"
        );
        var collection = collector.collect(planner.plan(recognition));
        var assessment = rootCauseAgent.analyze(recognition, collection);

        var plan = responsePlanAgent.plan(recognition, assessment, collection);

        assertThat(plan.actions()).extracting(action -> action.order())
                .containsExactly(java.util.stream.IntStream.rangeClosed(1, plan.actions().size()).boxed().toArray(Integer[]::new));
        assertThat(plan.actions()).anyMatch(action -> action.action().contains("2.4.1")
                && action.action().contains("2.4.0") && action.action().contains("回滚"));
        assertThat(plan.actions()).anyMatch(action -> action.action().contains("连接池容量"));
        assertThat(plan.actions().get(0).urgency()).isEqualTo(ActionUrgency.IMMEDIATE);
        assertThat(plan.followUpMetrics()).contains(
                "数据库连接等待线程数", "服务错误率", "P99 响应延迟", "用户请求成功率");
        assertThat(plan.summary()).contains("人工确认");
    }

    /** 健康服务只能得到核实和观察建议，不能产生回滚或立即变更动作。 */
    @Test
    void shouldAvoidRiskyActionsForHealthyService() {
        var recognition = parser.parse("订单服务产生了一条普通提醒");
        var collection = collector.collect(planner.plan(recognition));
        var assessment = rootCauseAgent.analyze(recognition, collection);

        var plan = responsePlanAgent.plan(recognition, assessment, collection);

        assertThat(plan.actions()).allMatch(action -> action.urgency() == ActionUrgency.OBSERVATION);
        assertThat(plan.actions()).noneMatch(action -> action.action().contains("回滚"));
        assertThat(plan.followUpMetrics()).contains("服务可用率", "健康实例数");
    }
}
