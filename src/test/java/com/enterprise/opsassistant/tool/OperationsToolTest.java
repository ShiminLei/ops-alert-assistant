package com.enterprise.opsassistant.tool;

import com.enterprise.opsassistant.domain.EvidenceStatus;
import com.enterprise.opsassistant.domain.ToolEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 运维工具层的单元测试。
 *
 * <p>测试直接组装 Mock 数据源和六个工具，不启动 Spring 容器，因此运行速度快，
 * 也能清楚验证工具接口本身的行为。</p>
 */
class OperationsToolTest {

    private final List<OperationsTool> tools;

    OperationsToolTest() {
        var dataStore = new com.enterprise.opsassistant.mock.MockOperationsDataStore();
        tools = List.of(
                new ServiceStatusTool(dataStore),
                new ErrorLogTool(dataStore),
                new DeploymentTool(dataStore),
                new ResourceUsageTool(dataStore),
                new DependencyStatusTool(dataStore),
                new DatabaseConnectionTool(dataStore)
        );
    }

    /** 验证六个工具都能针对同一故障服务产生完整且可追踪的成功证据。 */
    @Test
    void shouldCollectEvidenceFromAllSixTools() {
        List<ToolEvidence> evidence = tools.stream()
                .map(tool -> tool.execute("payment-service"))
                .toList();

        assertThat(evidence).hasSize(6);
        assertThat(evidence).allMatch(item -> item.status() == EvidenceStatus.SUCCESS);
        assertThat(evidence).extracting(ToolEvidence::toolName).doesNotHaveDuplicates();
        assertThat(evidence).extracting(ToolEvidence::evidenceId).doesNotHaveDuplicates();
        assertThat(evidence).allMatch(item -> !item.data().isEmpty());
    }

    /** 未知服务必须明确返回部分证据，不能静默伪造成健康状态。 */
    @Test
    void shouldReturnPartialEvidenceForUnknownService() {
        ToolEvidence evidence = tools.get(0).execute("missing-service");

        assertThat(evidence.status()).isEqualTo(EvidenceStatus.PARTIAL);
        assertThat(evidence.errorMessage()).contains("unknown service");
        assertThat(evidence.data()).containsEntry("dataAvailable", false);
    }

    /** 数据仓库中的健康对照服务应原样返回 HEALTHY，避免 Mock 工具天然偏向故障结论。 */
    @Test
    void shouldKeepHealthyServiceHealthy() {
        ToolEvidence evidence = tools.get(0).execute("order-service");

        assertThat(evidence.status()).isEqualTo(EvidenceStatus.SUCCESS);
        assertThat(evidence.data()).containsEntry("state", "HEALTHY");
    }
}
