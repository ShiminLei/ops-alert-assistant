package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 test 环境 YAML 能正确绑定成主备 Mock 模型并被路由器调用。 */
@ActiveProfiles("test")
@SpringBootTest
class AiConfigurationIntegrationTest {

    @Autowired
    private OpsAssistantAiProperties properties;

    @Autowired
    private AiClientRegistry registry;

    @Autowired
    private AiModelRouter router;

    @Test
    void shouldBindConfiguredMockProviders() {
        assertThat(properties.getPrimaryProvider()).isEqualTo("mock-primary");
        assertThat(properties.getBackupProvider()).isEqualTo("mock-backup");
        assertThat(registry.size()).isEqualTo(2);

        AiRoutingResult result = router.chatWithFallback(new AiChatRequest(
                "analysis-config-test",
                "ops-incident-review",
                List.of(new AiMessage(AiRole.USER, "测试模型配置"))
        ));

        assertThat(result.response().provider()).isEqualTo("mock-primary");
        assertThat(result.response().model()).isEqualTo("mock-ops-primary");
        assertThat(result.fallbackUsed()).isFalse();
    }
}
