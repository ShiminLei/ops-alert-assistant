package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.observability.OpsAssistantMetrics;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Spring AI Provider 调用外层的重试、限流和熔断保护都会真正执行。 */
class ResilientSpringAiClientInvokerTest {

    private final List<Message> messages = List.of(new UserMessage("请复核证据"));

    /** 前两次短暂失败时，第三次应由重试机制恢复，不需要立即切换备用模型。 */
    @Test
    void shouldRetryTransientProviderFailures() {
        CountingChatModel model = new CountingChatModel(2);
        ResilientSpringAiClientInvoker invoker = invoker(
                retryConfig(3), permissiveRateLimiter(), permissiveCircuitBreaker());

        AiReviewStructuredOutput response = invoker.invoke(
                provider(model), messages, AiReviewStructuredOutput.class);

        assertThat(response.evidenceConsistency()).isEqualTo("success");
        assertThat(model.invocations).isEqualTo(3);
    }

    /** 连续失败达到阈值后，熔断器应快速拒绝后续请求，不再调用真实模型。 */
    @Test
    void shouldOpenCircuitAfterRepeatedFailures() {
        CountingChatModel model = new CountingChatModel(Integer.MAX_VALUE);
        CircuitBreakerConfig circuitConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        ResilientSpringAiClientInvoker invoker = invoker(
                retryConfig(1), permissiveRateLimiter(), circuitConfig);

        assertThatThrownBy(() -> invoke(invoker, model)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invoke(invoker, model)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> invoke(invoker, model)).isInstanceOf(CallNotPermittedException.class);
        assertThat(model.invocations).isEqualTo(2);
    }

    /** 单个刷新周期的配额用完后，应立即拒绝而不是占用请求线程无限等待。 */
    @Test
    void shouldRejectCallsBeyondRateLimit() {
        CountingChatModel model = new CountingChatModel(0);
        RateLimiterConfig strictRateLimit = RateLimiterConfig.custom()
                .limitForPeriod(1)
                .limitRefreshPeriod(Duration.ofMinutes(1))
                .timeoutDuration(Duration.ZERO)
                .build();
        ResilientSpringAiClientInvoker invoker = invoker(
                retryConfig(1), strictRateLimit, permissiveCircuitBreaker());

        assertThat(invoke(invoker, model).evidenceConsistency()).isEqualTo("success");
        assertThatThrownBy(() -> invoke(invoker, model)).isInstanceOf(RequestNotPermitted.class);
        assertThat(model.invocations).isEqualTo(1);
    }

    private AiReviewStructuredOutput invoke(ResilientSpringAiClientInvoker invoker,
                                            ChatModel model) {
        return invoker.invoke(provider(model), messages, AiReviewStructuredOutput.class);
    }

    /** 每个测试使用独立注册表，避免熔断状态和限流配额在不同用例之间串扰。 */
    private ResilientSpringAiClientInvoker invoker(RetryConfig retryConfig,
                                                    RateLimiterConfig rateLimiterConfig,
                                                    CircuitBreakerConfig circuitBreakerConfig) {
        return new ResilientSpringAiClientInvoker(
                RetryRegistry.of(retryConfig),
                RateLimiterRegistry.of(rateLimiterConfig),
                CircuitBreakerRegistry.of(circuitBreakerConfig),
                OpsAssistantMetrics.noOp(),
                true
        );
    }

    private SpringAiProviderClient provider(ChatModel model) {
        return new SpringAiProviderClient(
                "primary", "primary-model", ChatClient.builder(model).build());
    }

    /** 测试容错机制时不等待，并避免对熔断或限流拒绝本身做无意义重试。 */
    private RetryConfig retryConfig(int maxAttempts) {
        return RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ZERO)
                .ignoreExceptions(CallNotPermittedException.class, RequestNotPermitted.class)
                .build();
    }

    private RateLimiterConfig permissiveRateLimiter() {
        return RateLimiterConfig.custom()
                .limitForPeriod(100)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO)
                .build();
    }

    private CircuitBreakerConfig permissiveCircuitBreaker() {
        return CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .failureRateThreshold(100)
                .build();
    }

    /** 可指定“前几次失败”并记录真实调用次数的 Spring AI 测试模型。 */
    private static class CountingChatModel implements ChatModel {
        private final int failuresBeforeSuccess;
        private int invocations;

        private CountingChatModel(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            invocations++;
            if (invocations <= failuresBeforeSuccess) {
                throw new IllegalStateException("temporary provider failure");
            }
            String json = """
                    {
                      "evidenceConsistency": "success",
                      "mostLikelyRootCause": "test",
                      "evidenceGaps": [],
                      "handlingNotes": []
                    }
                    """;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
        }
    }
}
