package com.enterprise.opsassistant.tool;

import com.enterprise.opsassistant.agent.EvidenceCollectorAgent;
import com.enterprise.opsassistant.mock.MockOperationsDataStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证六个运维查询已经成为可被 Spring AI 发现和执行的 Tool Calling 工具。 */
class SpringAiOperationsToolsTest {

    /**
     * 工具名称和数量是模型函数调用协议的一部分，重命名或遗漏都会直接影响模型选工具。
     * 同时实际调用一次回调，确认参数 JSON 能进入现有证据收集链路并返回结构化证据。
     */
    @Test
    void shouldExposeAndExecuteSixReadOnlySpringAiTools() {
        SpringAiOperationsTools operationsTools = operationsTools();

        ToolCallback[] callbacks = ToolCallbacks.from(operationsTools);

        assertThat(callbacks).hasSize(6);
        assertThat(Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name()))
                .containsExactlyInAnyOrder(
                        "query_service_status",
                        "query_error_logs",
                        "query_deployment",
                        "query_resource_usage",
                        "query_dependency_status",
                        "query_database_connections"
                );
        assertThat(Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().inputSchema()))
                .allMatch(schema -> schema.contains("serviceName"));

        ToolCallback serviceStatus = Arrays.stream(callbacks)
                .filter(callback -> callback.getToolDefinition().name()
                        .equals("query_service_status"))
                .findFirst()
                .orElseThrow();
        String resultJson = serviceStatus.call("{\"serviceName\":\"payment-service\"}");

        assertThat(resultJson)
                .contains("service-status")
                .contains("payment-service")
                .contains("DEGRADED");
    }

    /** 使用六个真实 Mock 查询工具组装适配器，测试过程不访问网络。 */
    private SpringAiOperationsTools operationsTools() {
        MockOperationsDataStore dataStore = new MockOperationsDataStore();
        List<OperationsTool> tools = List.of(
                new ServiceStatusTool(dataStore),
                new ErrorLogTool(dataStore),
                new DeploymentTool(dataStore),
                new ResourceUsageTool(dataStore),
                new DependencyStatusTool(dataStore),
                new DatabaseConnectionTool(dataStore)
        );
        return new SpringAiOperationsTools(new EvidenceCollectorAgent(tools));
    }
}
