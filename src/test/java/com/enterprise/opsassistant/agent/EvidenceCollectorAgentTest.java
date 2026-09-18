package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.EvidenceStatus;
import com.enterprise.opsassistant.domain.ToolPlan;
import com.enterprise.opsassistant.mock.MockOperationsDataStore;
import com.enterprise.opsassistant.tool.DatabaseConnectionTool;
import com.enterprise.opsassistant.tool.DeploymentTool;
import com.enterprise.opsassistant.tool.DependencyStatusTool;
import com.enterprise.opsassistant.tool.ErrorLogTool;
import com.enterprise.opsassistant.tool.OperationsTool;
import com.enterprise.opsassistant.tool.ResourceUsageTool;
import com.enterprise.opsassistant.tool.ServiceStatusTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证证据收集 Agent 会真实执行计划，并能隔离单个工具异常。 */
class EvidenceCollectorAgentTest {

    /** 六个真实 Mock 工具都应该被调用，并形成六条成功证据。 */
    @Test
    void shouldExecuteEveryPlannedTool() {
        EvidenceCollectorAgent collector = collectorWithRealTools();
        ToolPlan plan = new ToolPlan(
                "payment-service",
                AlertType.POST_DEPLOYMENT_FAILURE,
                List.of("service-status", "error-log", "deployment",
                        "resource-usage", "dependency-status", "database-connection"),
                "调查发布后的支付服务故障"
        );

        var result = collector.collect(plan);

        assertThat(result.evidence()).hasSize(6);
        assertThat(result.successCount()).isEqualTo(6);
        assertThat(result.failureCount()).isZero();
        assertThat(result.hasEnoughEvidence()).isTrue();
        assertThat(result.evidence()).extracting(item -> item.toolName())
                .containsExactlyElementsOf(plan.toolNames());
    }

    /** 计划引用不存在的工具时，应留下失败证据并继续执行其他工具。 */
    @Test
    void shouldKeepCollectingWhenOneToolIsMissing() {
        EvidenceCollectorAgent collector = collectorWithRealTools();
        ToolPlan plan = new ToolPlan(
                "payment-service",
                AlertType.UNKNOWN,
                List.of("service-status", "missing-tool", "resource-usage"),
                "验证异常隔离"
        );

        var result = collector.collect(plan);

        assertThat(result.evidence()).hasSize(3);
        assertThat(result.successCount()).isEqualTo(2);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.evidence().get(1).status()).isEqualTo(EvidenceStatus.FAILED);
    }

    /** 手工组装真实工具，保持单元测试快速且不依赖 Spring 容器启动。 */
    private EvidenceCollectorAgent collectorWithRealTools() {
        var dataStore = new MockOperationsDataStore();
        List<OperationsTool> tools = List.of(
                new ServiceStatusTool(dataStore),
                new ErrorLogTool(dataStore),
                new DeploymentTool(dataStore),
                new ResourceUsageTool(dataStore),
                new DependencyStatusTool(dataStore),
                new DatabaseConnectionTool(dataStore)
        );
        return new EvidenceCollectorAgent(tools);
    }
}
