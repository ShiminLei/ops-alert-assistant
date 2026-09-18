package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import com.enterprise.opsassistant.exception.AiProvidersUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证主备模型路由的三个关键分支，不发起任何真实网络请求。 */
class AiModelRouterTest {

    private final AiChatRequest request = new AiChatRequest(
            "analysis-1",
            "ops-incident-review",
            List.of(new AiMessage(AiRole.USER, "请复核运维证据"))
    );

    /** 主模型成功时不应调用备用模型。 */
    @Test
    void shouldUsePrimaryProviderFirst() {
        StubClient primary = new StubClient("primary", false);
        StubClient backup = new StubClient("backup", false);
        AiModelRouter router = router(primary, backup);

        AiRoutingResult result = router.chatWithFallback(request);

        assertThat(result.response().provider()).isEqualTo("primary");
        assertThat(result.fallbackUsed()).isFalse();
        assertThat(primary.invocations).isEqualTo(1);
        assertThat(backup.invocations).isZero();
    }

    /** 主模型抛出异常后应自动切换备用模型，并明确标记 fallbackUsed。 */
    @Test
    void shouldFallbackToBackupProvider() {
        StubClient primary = new StubClient("primary", true);
        StubClient backup = new StubClient("backup", false);
        AiModelRouter router = router(primary, backup);

        AiRoutingResult result = router.chatWithFallback(request);

        assertThat(result.response().provider()).isEqualTo("backup");
        assertThat(result.fallbackUsed()).isTrue();
        assertThat(primary.invocations).isEqualTo(1);
        assertThat(backup.invocations).isEqualTo(1);
    }

    /** 两个模型都失败时应抛出包含主备名称的聚合异常。 */
    @Test
    void shouldFailClearlyWhenBothProvidersAreUnavailable() {
        AiModelRouter router = router(
                new StubClient("primary", true),
                new StubClient("backup", true)
        );

        assertThatThrownBy(() -> router.chatWithFallback(request))
                .isInstanceOfSatisfying(AiProvidersUnavailableException.class, exception -> {
                    assertThat(exception.getPrimaryProvider()).isEqualTo("primary");
                    assertThat(exception.getBackupProvider()).isEqualTo("backup");
                    assertThat(exception.getSuppressed()).hasSize(1);
                });
    }

    /** 使用测试属性和客户端创建路由器。 */
    private AiModelRouter router(AiChatClient primary, AiChatClient backup) {
        OpsAssistantAiProperties properties = new OpsAssistantAiProperties();
        properties.setPrimaryProvider("primary");
        properties.setBackupProvider("backup");
        return new AiModelRouter(properties, new AiClientRegistry(List.of(primary, backup)));
    }

    /** 可计数、可指定失败的测试客户端。 */
    private static class StubClient implements AiChatClient {
        private final String provider;
        private final boolean fail;
        private int invocations;

        private StubClient(String provider, boolean fail) {
            this.provider = provider;
            this.fail = fail;
        }

        @Override
        public String providerName() {
            return provider;
        }

        @Override
        public String modelName() {
            return provider + "-model";
        }

        @Override
        public AiChatResponse chat(AiChatRequest request) {
            invocations++;
            if (fail) {
                throw new IllegalStateException(provider + " unavailable");
            }
            return new AiChatResponse("success", provider, modelName(), 1);
        }
    }
}
