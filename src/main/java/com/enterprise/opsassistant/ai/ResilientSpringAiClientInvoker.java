package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.observability.OpsAssistantMetrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
                        String conversationId,
                        List<Message> messages,
                        Class<T> outputType) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        long startedAt = System.nanoTime();
        Supplier<T> call = () -> provider.chatClient()
                .prompt()
                .messages(messages)
                .advisors(spec -> spec.param(
                        ChatMemory.CONVERSATION_ID, conversationId.trim()))
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

    /**
     * 使用 Spring AI {@code ChatClient.stream()} 执行一次可观察的 Structured Output 调用。
     *
     * <p>Spring AI 的同步 {@code entity(type)} 会自动添加输出格式并在收到完整响应后转换对象；
     * 流式 API 返回的是逐段文本，所以这里显式使用 {@link BeanOutputConverter} 完成同样的两步：
     * 先把目标 JSON Schema 加入提示词，再在流结束后把累计文本转换成指定 Java 类型。这样既能
     * 实时显示模型输出，又不会丢失最终报告依赖的强类型校验。</p>
     *
     * <p>整个“订阅流、收齐内容、结构化转换”仍放在 Resilience4j Supplier 内。任何网络错误、
     * 中途断流或 JSON 转换失败都会触发本 Provider 的重试。每次重试都会先发送 START，调用方
     * 因此可以丢弃上一尝试留下的半截内容。</p>
     */
    public <T> T invokeStreaming(SpringAiProviderClient provider,
                                 String analysisId,
                                 String conversationId,
                                 List<Message> messages,
                                 Class<T> outputType,
                                 boolean fallbackUsed,
                                 Consumer<AiReviewStreamEvent> observer) {
        if (analysisId == null || analysisId.isBlank()) {
            throw new IllegalArgumentException("analysisId must not be blank");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }

        BeanOutputConverter<T> converter = new BeanOutputConverter<>(outputType);
        List<Message> structuredMessages = new ArrayList<>(messages);
        structuredMessages.add(new UserMessage(
                "请严格按照下面的 JSON Schema 输出，只返回 JSON，不要添加 Markdown 代码围栏：\n"
                        + converter.getFormat()));

        long startedAt = System.nanoTime();
        Supplier<T> call = () -> {
            AtomicInteger chunkSequence = new AtomicInteger();
            emitStreamEvent(observer, new AiReviewStreamEvent(
                    analysisId.trim(), provider.providerName(), provider.modelName(), fallbackUsed,
                    AiReviewStreamPhase.START, 0, "", Instant.now()));

            String content = provider.chatClient()
                    .prompt()
                    .messages(structuredMessages)
                    .advisors(spec -> spec.param(
                            ChatMemory.CONVERSATION_ID, conversationId.trim()))
                    .stream()
                    .content()
                    .doOnNext(delta -> emitStreamEvent(observer, new AiReviewStreamEvent(
                            analysisId.trim(), provider.providerName(), provider.modelName(), fallbackUsed,
                            AiReviewStreamPhase.DELTA, chunkSequence.incrementAndGet(), delta, Instant.now())))
                    .collectList()
                    .map(parts -> String.join("", parts))
                    .block();

            if (content == null || content.isBlank()) {
                throw new IllegalStateException(
                        "Spring AI provider returned an empty streaming result: "
                                + provider.providerName());
            }
            T result = converter.convert(content);
            if (result == null) {
                throw new IllegalStateException(
                        "Spring AI provider returned an empty structured streaming result: "
                                + provider.providerName());
            }
            emitStreamEvent(observer, new AiReviewStreamEvent(
                    analysisId.trim(), provider.providerName(), provider.modelName(), fallbackUsed,
                    AiReviewStreamPhase.COMPLETE, chunkSequence.get(), "", Instant.now()));
            return result;
        };

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
            metrics.recordAiProviderCall(provider.providerName(), "success", elapsedSince(startedAt));
            return result;
        } catch (RuntimeException exception) {
            metrics.recordAiProviderCall(provider.providerName(), "failure", elapsedSince(startedAt));
            throw exception;
        }
    }

    /**
     * 流式观察者属于展示通道，浏览器断开或观察者自身异常不能反向把成功模型调用标记为失败。
     */
    private void emitStreamEvent(Consumer<AiReviewStreamEvent> observer,
                                 AiReviewStreamEvent event) {
        if (observer == null) {
            return;
        }
        try {
            observer.accept(event);
        } catch (RuntimeException observerException) {
            // SSE 客户端断开是正常网络事件；核心分析仍需继续完成并留下可审计日志。
        }
    }

    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
