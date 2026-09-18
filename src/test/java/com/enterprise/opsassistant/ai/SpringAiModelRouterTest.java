package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import com.enterprise.opsassistant.exception.AiProvidersUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证 Spring AI 主备路由的三个关键分支。
 *
 * <p>这里不启动 Spring 容器，也不启用重试、限流和熔断，只验证路由决策本身。成功模型仍然
 * 经过真实 {@link ChatClient} 和 Structured Output 转换，因此测试能够发现客户端调用或 JSON
 * 反序列化链路被破坏的问题。</p>
 */
class SpringAiModelRouterTest {

    /** 主模型正常时应立即返回，且结果不能被误标记为降级。 */
    @Test
    void shouldUsePrimaryProviderWhenPrimarySucceeds() {
        SpringAiModelRouter router = router(
                new DeterministicOpsChatModel(), new DeterministicOpsChatModel());

        SpringAiRoutingResult<AiReviewStructuredOutput> result = call(router);

        assertThat(result.provider()).isEqualTo("primary");
        assertThat(result.model()).isEqualTo("primary-model");
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(result.body().evidenceConsistency()).contains("Spring AI 结构化复核链路");
    }

    /** 主模型抛异常时应自动调用备用模型，并在返回值中留下可观测的降级标记。 */
    @Test
    void shouldUseBackupProviderWhenPrimaryFails() {
        SpringAiModelRouter router = router(
                failingModel("primary unavailable"), new DeterministicOpsChatModel());

        SpringAiRoutingResult<AiReviewStructuredOutput> result = call(router);

        assertThat(result.provider()).isEqualTo("backup");
        assertThat(result.model()).isEqualTo("backup-model");
        assertThat(result.fallbackUsed()).isTrue();
    }

    /** 主备都失败时应抛出聚合异常，让上层明确退回 Java 安全规则结果。 */
    @Test
    void shouldExposeBothProviderNamesWhenAllProvidersFail() {
        SpringAiModelRouter router = router(
                failingModel("primary unavailable"), failingModel("backup unavailable"));

        assertThatThrownBy(() -> call(router))
                .isInstanceOfSatisfying(AiProvidersUnavailableException.class, exception -> {
                    assertThat(exception.getPrimaryProvider()).isEqualTo("primary");
                    assertThat(exception.getBackupProvider()).isEqualTo("backup");
                    assertThat(exception.getSuppressed()).hasSize(1);
                });
    }

    private SpringAiRoutingResult<AiReviewStructuredOutput> call(SpringAiModelRouter router) {
        return router.callWithFallback(
                List.of(new UserMessage("请复核测试告警")),
                AiReviewStructuredOutput.class);
    }

    private SpringAiModelRouter router(ChatModel primaryModel, ChatModel backupModel) {
        OpsAssistantAiProperties properties = new OpsAssistantAiProperties();
        properties.setPrimaryProvider("primary");
        properties.setBackupProvider("backup");

        SpringAiClientRegistry registry = new SpringAiClientRegistry(List.of(
                provider("primary", "primary-model", primaryModel),
                provider("backup", "backup-model", backupModel)
        ));
        return new SpringAiModelRouter(properties, registry);
    }

    private SpringAiProviderClient provider(String providerName,
                                            String modelName,
                                            ChatModel model) {
        return new SpringAiProviderClient(
                providerName, modelName, ChatClient.builder(model).build());
    }

    /** 创建一个始终失败的 Spring AI 模型，用于稳定复现供应商故障。 */
    private ChatModel failingModel(String message) {
        return prompt -> {
            throw new IllegalStateException(message);
        };
    }
}
