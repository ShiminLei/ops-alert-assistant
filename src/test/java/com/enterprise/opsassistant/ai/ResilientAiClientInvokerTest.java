package com.enterprise.opsassistant.ai;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 AI Provider 的重试、限流和熔断保护都会真正执行。 */
class ResilientAiClientInvokerTest {

    private final AiChatRequest request = new AiChatRequest(
            "analysis-1",
            "ops-incident-review",
            List.of(new AiMessage(AiRole.USER, "请复核证据"))
    );

    /** 前两次短暂失败时，第三次应由重试机制恢复，不需要立即切换备用模型。 */
    @Test
    void shouldRetryTransientProviderFailures() {
        CountingClient client = new CountingClient("primary", 2);
        ResilientAiClientInvoker invoker = invoker(
                client,
                retryConfig(3),
                permissiveRateLimiter(),
                permissiveCircuitBreaker()
        );

        AiChatResponse response = invoker.invoke("primary", request);

        assertThat(response.content()).isEqualTo("success");
        assertThat(client.invocations).isEqualTo(3);
    }

    /** 连续失败达到阈值后，熔断器应快速拒绝后续请求，不再调用真实客户端。 */
    @Test
    void shouldOpenCircuitAfterRepeatedFailures() {
        CountingClient client = new CountingClient("primary", Integer.MAX_VALUE);
        CircuitBreakerConfig circuitConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        ResilientAiClientInvoker invoker = invoker(
                client,
                retryConfig(1),
                permissiveRateLimiter(),
                circuitConfig
        );

        assertThatThrownBy(() -> invoker.invoke("primary", request))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invoker.invoke("primary", request))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invoker.invoke("primary", request))
                .isInstanceOf(CallNotPermittedException.class);
        assertThat(client.invocations).isEqualTo(2);
    }

    /** 单个刷新周期的配额用完后，应立即拒绝而不是无限排队等待。 */
    @Test
    void shouldRejectCallsBeyondRateLimit() {
        CountingClient client = new CountingClient("primary", 0);
        RateLimiterConfig strictRateLimit = RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();
        ResilientAiClientInvoker invoker = invoker(
                client,
                retryConfig(1),
                strictRateLimit,
                permissiveCircuitBreaker()
        );

        assertThat(invoker.invoke("primary", request).content()).isEqualTo("success");
        assertThatThrownBy(() -> invoker.invoke("primary", request))
                .isInstanceOf(RequestNotPermitted.class);
        assertThat(client.invocations).isEqualTo(1);
    }

    /** 使用每个测试自己的注册表，避免容错状态在测试之间串扰。 */
    private ResilientAiClientInvoker invoker(CountingClient client,
                                              RetryConfig retryConfig,
                                              RateLimiterConfig rateLimiterConfig,
                                              CircuitBreakerConfig circuitBreakerConfig) {
        return new ResilientAiClientInvoker(
                new AiClientRegistry(List.of(client)),
                RetryRegistry.of(retryConfig),
                RateLimiterRegistry.of(rateLimiterConfig),
                CircuitBreakerRegistry.of(circuitBreakerConfig),
                true
        );
    }

    /** 测试容错机制时不等待，但仍忽略熔断和限流拒绝，避免对拒绝本身做无意义重试。 */
    private RetryConfig retryConfig(int maxAttempts) {
        return RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ZERO)
                .ignoreExceptions(CallNotPermittedException.class, RequestNotPermitted.class)
                .build();
    }

    /** 其他测试不关心限流时，提供足够大的即时配额。 */
    private RateLimiterConfig permissiveRateLimiter() {
        return RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();
    }

    /** 其他测试不关心熔断时，提高最低调用次数防止提前打开。 */
    private CircuitBreakerConfig permissiveCircuitBreaker() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .failureRateThreshold(100)
                .build();
    }

    /** 可指定“前几次失败”并记录真实调用次数的测试客户端。 */
    private static class CountingClient implements AiChatClient {
        private final String providerName;
        private final int failuresBeforeSuccess;
        private int invocations;

        private CountingClient(String providerName, int failuresBeforeSuccess) {
            this.providerName = providerName;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public String providerName() {
            return providerName;
        }

        @Override
        public String modelName() {
            return providerName + "-model";
        }

        @Override
        public AiChatResponse chat(AiChatRequest request) {
            invocations++;
            if (invocations <= failuresBeforeSuccess) {
                throw new IllegalStateException("temporary provider failure");
            }
            return new AiChatResponse("success", providerName, modelName(), 1);
        }
    }
}
