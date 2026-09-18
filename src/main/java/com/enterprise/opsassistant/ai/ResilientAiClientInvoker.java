package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.observability.OpsAssistantMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 为单次 AI Provider 调用增加重试、限流和熔断保护。
 *
 * <p>三种保护的职责不同：重试用来吸收短暂网络抖动；限流防止瞬时请求压垮模型
 * 服务或超过厂商配额；熔断器在连续失败时快速拒绝请求，避免每次都等待超时。</p>
 *
 * <p>保护实例按 Provider 名称隔离。例如 primary 的熔断器打开时，backup 仍有自己独立的
 * 状态，因此 {@link AiModelRouter} 仍然可以把请求降级到备用模型。</p>
 */
@Component
public class ResilientAiClientInvoker {

    private final AiClientRegistry clientRegistry;
    private final RetryRegistry retryRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final OpsAssistantMetrics metrics;
    private final boolean resilienceEnabled;

    /** Spring 生产运行时注入 Resilience4j 的三个注册表。 */
    @Autowired
    public ResilientAiClientInvoker(AiClientRegistry clientRegistry,
                                    RetryRegistry retryRegistry,
                                    RateLimiterRegistry rateLimiterRegistry,
                                    CircuitBreakerRegistry circuitBreakerRegistry,
                                    OpsAssistantMetrics metrics) {
        this(clientRegistry, retryRegistry, rateLimiterRegistry, circuitBreakerRegistry, metrics, true);
    }

    /**
     * 显式接收注册表的构造器，使单元测试可以用更小的阈值验证重试、限流和熔断。
     */
    ResilientAiClientInvoker(AiClientRegistry clientRegistry,
                             RetryRegistry retryRegistry,
                             RateLimiterRegistry rateLimiterRegistry,
                             CircuitBreakerRegistry circuitBreakerRegistry,
                             boolean resilienceEnabled) {
        this(clientRegistry, retryRegistry, rateLimiterRegistry, circuitBreakerRegistry,
                OpsAssistantMetrics.noOp(), resilienceEnabled);
    }

    /** 完整构造器集中保存容错组件和指标组件，便于生产与测试共用同一调用逻辑。 */
    private ResilientAiClientInvoker(AiClientRegistry clientRegistry,
                                     RetryRegistry retryRegistry,
                                     RateLimiterRegistry rateLimiterRegistry,
                                     CircuitBreakerRegistry circuitBreakerRegistry,
                                     OpsAssistantMetrics metrics,
                                     boolean resilienceEnabled) {
        this.clientRegistry = Objects.requireNonNull(clientRegistry, "clientRegistry must not be null");
        this.retryRegistry = retryRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.resilienceEnabled = resilienceEnabled;
    }

    /**
     * 创建不启用容错策略的调用器，仅供专注于路由逻辑的单元测试使用。
     */
    static ResilientAiClientInvoker direct(AiClientRegistry clientRegistry) {
        return new ResilientAiClientInvoker(clientRegistry, null, null, null,
                OpsAssistantMetrics.noOp(), false);
    }

    /**
     * 调用指定 Provider，并使一次重试也重新经过限流和熔断检查。
     *
     * <p>装饰顺序从内到外为：真实调用→熔断记录→限流放行→重试。因此每次重试
     * 都必须重新获得限流许可，也会被计入熔断器的成功或失败统计。</p>
     */
    public AiChatResponse invoke(String providerName, AiChatRequest request) {
        long startedAt = System.nanoTime();
        AiChatClient client = clientRegistry.find(providerName)
                .orElseThrow(() -> new IllegalStateException(
                        "AI provider is not registered: " + providerName));

        if (!resilienceEnabled) {
            return invokeAndRecord(client, providerName, request, startedAt);
        }

        String instanceName = "ai-provider-" + providerName.trim().toLowerCase(Locale.ROOT);
        CircuitBreaker circuitBreaker = circuitBreakerRegistry.circuitBreaker(instanceName);
        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter(instanceName);
        Retry retry = retryRegistry.retry(instanceName);

        Supplier<AiChatResponse> protectedCall = () -> client.chat(request);
        protectedCall = CircuitBreaker.decorateSupplier(circuitBreaker, protectedCall);
        protectedCall = RateLimiter.decorateSupplier(rateLimiter, protectedCall);
        protectedCall = Retry.decorateSupplier(retry, protectedCall);
        try {
            AiChatResponse response = protectedCall.get();
            metrics.recordAiProviderCall(providerName, "success", elapsedSince(startedAt));
            return response;
        } catch (RuntimeException exception) {
            metrics.recordAiProviderCall(providerName, "failure", elapsedSince(startedAt));
            throw exception;
        }
    }

    /** 不启用 Resilience4j 的测试调用仍使用相同的成败指标语义。 */
    private AiChatResponse invokeAndRecord(AiChatClient client,
                                           String providerName,
                                           AiChatRequest request,
                                           long startedAt) {
        try {
            AiChatResponse response = client.chat(request);
            metrics.recordAiProviderCall(providerName, "success", elapsedSince(startedAt));
            return response;
        } catch (RuntimeException exception) {
            metrics.recordAiProviderCall(providerName, "failure", elapsedSince(startedAt));
            throw exception;
        }
    }

    /** 使用单调递增时钟计算耗时，避免系统时间被调整导致负耗时。 */
    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
