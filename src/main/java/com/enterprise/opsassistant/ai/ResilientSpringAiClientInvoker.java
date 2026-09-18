package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.observability.OpsAssistantMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 为单个 Spring AI ChatClient 调用增加 Provider 级容错保护。
 *
 * <p>Spring AI 负责模型协议、结构化输出、工具调用和观测；Resilience4j 负责企业运行策略：
 * 重试吸收短暂抖动，限流保护模型配额，熔断避免故障期间持续等待。每个 Provider 使用独立实例，
 * 因此主模型熔断不会阻止备用模型接管。</p>
 */
@Component
public class ResilientSpringAiClientInvoker {

    private final RetryRegistry retryRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final OpsAssistantMetrics metrics;
    private final boolean resilienceEnabled;

    @Autowired
    public ResilientSpringAiClientInvoker(RetryRegistry retryRegistry,
                                          RateLimiterRegistry rateLimiterRegistry,
                                          CircuitBreakerRegistry circuitBreakerRegistry,
                                          OpsAssistantMetrics metrics) {
        this(retryRegistry, rateLimiterRegistry, circuitBreakerRegistry, metrics, true);
    }

    /** 测试可传入独立注册表，避免熔断或限流状态在用例之间相互影响。 */
    ResilientSpringAiClientInvoker(RetryRegistry retryRegistry,
                                   RateLimiterRegistry rateLimiterRegistry,
                                   CircuitBreakerRegistry circuitBreakerRegistry,
                                   OpsAssistantMetrics metrics,
                                   boolean resilienceEnabled) {
        this.retryRegistry = retryRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.metrics = metrics;
        this.resilienceEnabled = resilienceEnabled;
    }

    /** 创建不启用 Resilience4j 的调用器，让路由单元测试只关注主备顺序。 */
    static ResilientSpringAiClientInvoker direct() {
        return new ResilientSpringAiClientInvoker(
                null, null, null, OpsAssistantMetrics.noOp(), false);
    }

    /**
     * 执行一次 Spring AI Structured Output 调用。
     *
     * <p>实体转换也位于受保护调用内部。模型即使返回了无法转换的 JSON，也会被视为该 Provider
     * 本轮失败，从而允许备用模型提供一份格式正确的结果。</p>
     */
    public <T> T invoke(SpringAiProviderClient provider,
                        List<Message> messages,
                        Class<T> outputType) {
        long startedAt = System.nanoTime();
        Supplier<T> call = () -> provider.chatClient()
                .prompt()
                .messages(messages)
                .call()
                .entity(outputType);

        if (resilienceEnabled) {
            String instanceName = "ai-provider-"
                    + provider.providerName().toLowerCase(Locale.ROOT);
            CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(instanceName);
            RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(instanceName);
            Retry retry = retryRegistry.retry(instanceName);
            call = CircuitBreaker.decorateSupplier(circuitBreaker, call);
            call = RateLimiter.decorateSupplier(rateLimiter, call);
            call = Retry.decorateSupplier(retry, call);
        }

        try {
            T result = call.get();
            if (result == null) {
                throw new IllegalStateException(
                        "Spring AI provider returned an empty structured result: "
                                + provider.providerName());
            }
            metrics.recordAiProviderCall(provider.providerName(), "success", elapsedSince(startedAt));
            return result;
        } catch (RuntimeException exception) {
            metrics.recordAiProviderCall(provider.providerName(), "failure", elapsedSince(startedAt));
            throw exception;
        }
    }

    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
