package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
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
    private SpringAiClientRegistry registry;

    @Autowired
    private SpringAiModelRouter router;

    @Autowired
    private RetryRegistry retryRegistry;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Test
    void shouldBindConfiguredMockProviders() {
        assertThat(properties.getPrimaryProvider()).isEqualTo("mock-primary");
        assertThat(properties.getBackupProvider()).isEqualTo("mock-backup");
        assertThat(registry.size()).isEqualTo(2);

        SpringAiRoutingResult<AiReviewStructuredOutput> result = router.callWithFallback(
                "conversation-config-test",
                List.of(new UserMessage("测试模型配置")),
                AiReviewStructuredOutput.class);

        assertThat(result.provider()).isEqualTo("mock-primary");
        assertThat(result.model()).isEqualTo("mock-ops-primary");
        assertThat(result.fallbackUsed()).isFalse();
    }

    /** 验证 application.yml 的默认容错参数确实进入 Resilience4j 运行时注册表。 */
    @Test
    void shouldBindDefaultResiliencePolicies() {
        String instanceName = "ai-provider-mock-primary";

        assertThat(retryRegistry.retry(instanceName).getRetryConfig().getMaxAttempts())
                .isEqualTo(3);
        assertThat(rateLimiterRegistry.rateLimiter(instanceName)
                .getRateLimiterConfig().getLimitForPeriod())
                .isEqualTo(20);
        assertThat(circuitBreakerRegistry.circuitBreaker(instanceName)
                .getCircuitBreakerConfig().getSlidingWindowSize())
                .isEqualTo(10);
        assertThat(circuitBreakerRegistry.circuitBreaker(instanceName)
                .getCircuitBreakerConfig().getMinimumNumberOfCalls())
                .isEqualTo(5);
        assertThat(circuitBreakerRegistry.circuitBreaker(instanceName)
                .getCircuitBreakerConfig().getFailureRateThreshold())
                .isEqualTo(50.0f);
    }
}
