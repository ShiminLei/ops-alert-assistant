package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.domain.AnalysisProgressEvent;
import com.enterprise.opsassistant.domain.AnalysisStage;
import com.enterprise.opsassistant.domain.RiskLevel;
import com.enterprise.opsassistant.exception.InvalidAlertException;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 SupervisorAgent 会按固定顺序编排完整多 Agent 分析链。 */
class SupervisorAgentTest {

    private SupervisorAgent supervisor;

    /** 使用全部真实规则 Agent 和 Mock 工具组装总编排器，不伪造中间结果。 */
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
        supervisor = new SupervisorAgent(
                new AlertParserAgent(),
                new ToolPlanningAgent(),
                new EvidenceCollectorAgent(tools),
                new RootCauseAgent(),
                new ResponsePlanAgent()
        );
    }

    /** 一次自然语言输入应生成包含证据、根因、动作和模型来源的最终报告。 */
    @Test
    void shouldRunCompleteAnalysisPipeline() {
        List<AnalysisProgressEvent> events = new ArrayList<>();

        var report = supervisor.analyze(
                "支付服务刚发布后大量请求超时，错误率18.7%，用户支付失败",
                events::add
        );

        assertThat(report.analysisId()).isNotBlank();
        assertThat(report.recognition().serviceName()).isEqualTo("payment-service");
        assertThat(report.evidence()).hasSize(6);
        assertThat(report.rootCause().finalRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(report.rootCause().rollbackRecommended()).isTrue();
        assertThat(report.recommendedActions()).isNotEmpty();
        assertThat(report.followUpMetrics()).isNotEmpty();
        assertThat(report.modelProvider()).isEqualTo("rule-engine");
        assertThat(report.modelName()).isEqualTo("deterministic-safety-model-v1");
        assertThat(report.fallbackUsed()).isFalse();
        assertThat(events).allMatch(event -> event.analysisId().equals(report.analysisId()));
        assertThat(events).extracting(AnalysisProgressEvent::stage).containsExactly(
                AnalysisStage.RECEIVED,
                AnalysisStage.ALERT_RECOGNIZED,
                AnalysisStage.TOOLS_PLANNED,
                AnalysisStage.EVIDENCE_COLLECTED,
                AnalysisStage.ROOT_CAUSE_ANALYZED,
                AnalysisStage.AI_REVIEWED,
                AnalysisStage.REPORT_GENERATED,
                AnalysisStage.COMPLETED
        );
    }

    /** 无效输入应产生 RECEIVED 和 FAILED 事件，并保留明确的输入异常类型。 */
    @Test
    void shouldPublishFailedStageForInvalidAlert() {
        List<AnalysisProgressEvent> events = new ArrayList<>();

        assertThatThrownBy(() -> supervisor.analyze("  ", events::add))
                .isInstanceOf(InvalidAlertException.class)
                .hasMessageContaining("must not be blank");
        assertThat(events).extracting(AnalysisProgressEvent::stage)
                .containsExactly(AnalysisStage.RECEIVED, AnalysisStage.FAILED);
        assertThat(events.get(0).analysisId()).isEqualTo(events.get(1).analysisId());
    }

    /** 前端或其他事件观察者异常不能反向破坏核心报告生成。 */
    @Test
    void shouldContinueWhenProgressObserverFails() {
        var report = supervisor.analyze(
                "订单服务产生了一条普通提醒",
                event -> { throw new IllegalStateException("client disconnected"); }
        );

        assertThat(report.recognition().serviceName()).isEqualTo("order-service");
        assertThat(report.rootCause().finalRisk()).isEqualTo(RiskLevel.LOW);
    }
}
