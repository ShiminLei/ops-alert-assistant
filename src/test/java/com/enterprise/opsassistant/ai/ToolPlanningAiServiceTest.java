package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.RiskLevel;
import com.enterprise.opsassistant.tool.OperationsTool;
import com.enterprise.opsassistant.tool.OperationsToolCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证工具规划阶段真实经过 Spring AI Structured Output 调用链。 */
class ToolPlanningAiServiceTest {

    @Test
    void shouldCallSpringAiAndConvertToolPlan() {
        OpsAssistantAiProperties properties = new OpsAssistantAiProperties();
        properties.setPrimaryProvider("primary");
        properties.setBackupProvider("backup");
        SpringAiClientRegistry registry = new SpringAiClientRegistry(List.of(
                provider("primary", "primary-model"),
                provider("backup", "backup-model")
        ));
        ToolPlanningAiService service = new ToolPlanningAiService(
                new SpringAiModelRouter(properties, registry),
                catalog(),
                new ObjectMapper().findAndRegisterModules());

        var result = service.plan(
                "conversation-tool-planning",
                new AlertRecognition(
                        "payment-service", AlertType.POST_DEPLOYMENT_FAILURE,
                        List.of(), RiskLevel.HIGH, true, true, "发布后支付失败"));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().toolNames())
                .containsExactly(
                        "service-status", "error-log", "resource-usage",
                        "deployment", "database-connection", "dependency-status");
        assertThat(result.orElseThrow().rationale()).contains("Spring AI Structured Output");
    }

    private SpringAiProviderClient provider(String name, String model) {
        return new SpringAiProviderClient(
                name, model, ChatClient.builder(new DeterministicOpsChatModel()).build());
    }

    private OperationsToolCatalog catalog() {
        return new OperationsToolCatalog(List.of(
                tool("service-status"), tool("error-log"), tool("resource-usage"),
                tool("deployment"), tool("dependency-status"), tool("database-connection")
        ));
    }

    private OperationsTool tool(String name) {
        OperationsTool tool = mock(OperationsTool.class);
        when(tool.name()).thenReturn(name);
        return tool;
    }
}
