package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.RiskLevel;
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

/**
 * 使用从自然语言到真实 Mock 工具的完整前置链路验证 RootCauseAgent，
 * 避免只构造迎合规则的零散测试数据。
 */
class RootCauseAgentTest {

    private final AlertParserAgent parser = new AlertParserAgent();
    private final ToolPlanningAgent planner = new ToolPlanningAgent();
    private final RootCauseAgent rootCauseAgent = new RootCauseAgent();
    private EvidenceCollectorAgent collector;

    /** 每个测试都组装六个真实 Mock 工具，确保根因判断基于工具实际返回值。 */
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

    /** 发布、日志和数据库证据应共同指向连接池耗尽，并产生回滚和升级建议。 */
    @Test
    void shouldFindDatabasePoolExhaustionFromCorrelatedEvidence() {
        AlertRecognition recognition = parser.parse(
                "支付服务刚发布后大量请求超时，错误率18.7%，用户支付失败"
        );
        var collection = collector.collect(planner.plan(recognition));

        var assessment = rootCauseAgent.analyze(recognition, collection);

        assertThat(assessment.finalRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(assessment.candidates().get(0).description()).contains("数据库连接池");
        assertThat(assessment.candidates().get(0).confidence()).isEqualTo(0.95);
        assertThat(assessment.candidates().get(0).evidenceIds()).hasSize(3);
        assertThat(assessment.rollbackRecommended()).isTrue();
        assertThat(assessment.escalationRequired()).isTrue();
        assertThat(assessment.userImpact()).contains("18.7%");
        assertThat(assessment.reasoning()).anyMatch(item -> item.contains("相互印证"));
    }

    /** 健康服务的普通提醒不应被规则错误升级为高风险，也不应建议回滚。 */
    @Test
    void shouldRemainConservativeForHealthyService() {
        AlertRecognition recognition = parser.parse("订单服务产生了一条普通提醒");
        var collection = collector.collect(planner.plan(recognition));

        var assessment = rootCauseAgent.analyze(recognition, collection);

        assertThat(assessment.finalRisk()).isEqualTo(RiskLevel.LOW);
        assertThat(assessment.candidates()).hasSize(1);
        assertThat(assessment.candidates().get(0).confidence()).isEqualTo(0.20);
        assertThat(assessment.rollbackRecommended()).isFalse();
        assertThat(assessment.escalationRequired()).isFalse();
        assertThat(assessment.userImpact()).isEqualTo("尚未确认用户影响");
    }
}
