package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.catalog.InMemoryServiceCatalog;
import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证告警理解阶段真实经过 Spring AI ChatClient 和 Structured Output 转换。 */
class AlertUnderstandingAiServiceTest {

    /**
     * 本地确定性模型不会做真实语义推理，但必须返回告警理解专用结构，而不是复核阶段的 JSON。
     * 这能防止代码看似注入 AI Service，运行时却因为输出类型不匹配而永远偷偷降级。
     */
    @Test
    void shouldCallSpringAiAndConvertAlertUnderstandingOutput() {
        OpsAssistantAiProperties properties = new OpsAssistantAiProperties();
        properties.setPrimaryProvider("primary");
        properties.setBackupProvider("backup");

        SpringAiClientRegistry registry = new SpringAiClientRegistry(List.of(
                provider("primary", "primary-model"),
                provider("backup", "backup-model")
        ));
        AlertUnderstandingAiService service = new AlertUnderstandingAiService(
                new SpringAiModelRouter(properties, registry),
                new InMemoryServiceCatalog());

        var result = service.understand(
                "conversation-alert-understanding",
                "支付服务刚发布后错误率达到 18.7%");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().summary())
                .contains("Spring AI 告警理解");
        assertThat(result.orElseThrow().alertType()).isEqualTo(
                com.enterprise.opsassistant.domain.AlertType.UNKNOWN);
    }

    private SpringAiProviderClient provider(String providerName, String modelName) {
        return new SpringAiProviderClient(
                providerName,
                modelName,
                ChatClient.builder(new DeterministicOpsChatModel()).build());
    }
}
