package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import com.enterprise.opsassistant.exception.AiProvidersUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/**
 * 使用两个 Spring AI ChatClient 实现主模型优先、备用模型接管。
 *
 * <p>Spring AI 提供多模型客户端，但不会替企业决定“哪些异常需要切换、主备顺序是什么、结果如何
 * 标记”。本路由器只补上这层业务策略；每次真正的模型调用仍由 Spring AI 完成。</p>
 */
@Component
public class SpringAiModelRouter {

    private static final Logger log = LoggerFactory.getLogger(SpringAiModelRouter.class);

    private final OpsAssistantAiProperties properties;
    private final SpringAiClientRegistry registry;
    private final ResilientSpringAiClientInvoker invoker;

    @Autowired
    public SpringAiModelRouter(OpsAssistantAiProperties properties,
                               SpringAiClientRegistry registry,
                               ResilientSpringAiClientInvoker invoker) {
        this.properties = properties;
        this.registry = registry;
        this.invoker = invoker;
    }

    /** 路由单元测试使用的便捷构造器，不启用重试以便准确断言调用次数。 */
    SpringAiModelRouter(OpsAssistantAiProperties properties,
                        SpringAiClientRegistry registry) {
        this(properties, registry, ResilientSpringAiClientInvoker.direct());
    }

    /**
     * 先调用主 Provider；调用、实体转换或容错组件抛出异常时，再调用备用 Provider。
     */
    public <T> SpringAiRoutingResult<T> callWithFallback(String conversationId,
                                                         List<Message> messages,
                                                         Class<T> outputType) {
        String primaryName = requireProviderName(
                properties.getPrimaryProvider(), "primary-provider");
        String backupName = requireProviderName(
                properties.getBackupProvider(), "backup-provider");

        RuntimeException primaryFailure;
        try {
            return invoke(primaryName, conversationId, messages, outputType, false);
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            log.warn("Spring AI 主模型调用失败，准备切换备用模型: primary={}, reason={}",
                    primaryName, exception.toString());
        }

        if (primaryName.equalsIgnoreCase(backupName)) {
            throw new AiProvidersUnavailableException(
                    primaryName,
                    backupName,
                    primaryFailure,
                    new IllegalStateException("backup provider must differ from primary provider"));
        }

        try {
            SpringAiRoutingResult<T> result = invoke(
                    backupName, conversationId, messages, outputType, true);
            log.info("Spring AI 备用模型接管成功: primary={}, backup={}, model={}",
                    primaryName, backupName, result.model());
            return result;
        } catch (RuntimeException backupFailure) {
            log.error("Spring AI 主备模型均调用失败: primary={}, backup={}",
                    primaryName, backupName);
            throw new AiProvidersUnavailableException(
                    primaryName, backupName, primaryFailure, backupFailure);
        }
    }

    /**
     * 主备路由规则不变，但底层使用 Spring AI 流式调用并实时通知模型输出。
     *
     * <p>主模型的流如果中途失败，Invoker 的最后一次 START/DELTA 可能已经到达前端；备用模型
     * 开始时会发送新的 START 且 {@code fallbackUsed=true}。前端以 START 作为重置边界，因此
     * 不会把失败主模型的半截 JSON 与备用模型结果混合。</p>
     */
    public <T> SpringAiRoutingResult<T> streamWithFallback(
            String analysisId,
            String conversationId,
            List<Message> messages,
            Class<T> outputType,
            Consumer<AiReviewStreamEvent> observer) {
        String primaryName = requireProviderName(
                properties.getPrimaryProvider(), "primary-provider");
        String backupName = requireProviderName(
                properties.getBackupProvider(), "backup-provider");

        RuntimeException primaryFailure;
        try {
            return invokeStreaming(primaryName, analysisId, conversationId,
                    messages, outputType, false, observer);
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            log.warn("Spring AI 主模型流式调用失败，准备切换备用模型: primary={}, reason={}",
                    primaryName, exception.toString());
        }

        if (primaryName.equalsIgnoreCase(backupName)) {
            throw new AiProvidersUnavailableException(
                    primaryName,
                    backupName,
                    primaryFailure,
                    new IllegalStateException("backup provider must differ from primary provider"));
        }

        try {
            SpringAiRoutingResult<T> result = invokeStreaming(
                    backupName, analysisId, conversationId,
                    messages, outputType, true, observer);
            log.info("Spring AI 备用模型流式接管成功: primary={}, backup={}, model={}",
                    primaryName, backupName, result.model());
            return result;
        } catch (RuntimeException backupFailure) {
            log.error("Spring AI 主备模型流式调用均失败: primary={}, backup={}",
                    primaryName, backupName);
            throw new AiProvidersUnavailableException(
                    primaryName, backupName, primaryFailure, backupFailure);
        }
    }

    private <T> SpringAiRoutingResult<T> invoke(String providerName,
                                                String conversationId,
                                                List<Message> messages,
                                                Class<T> outputType,
                                                boolean fallbackUsed) {
        SpringAiProviderClient provider = registry.find(providerName)
                .orElseThrow(() -> new IllegalStateException(
                        "Spring AI provider is not registered: " + providerName));
        T body = invoker.invoke(provider, conversationId, messages, outputType);
        return new SpringAiRoutingResult<>(
                body, provider.providerName(), provider.modelName(), fallbackUsed);
    }

    /** 查找指定 Provider，并保留其名称、模型名称以及是否降级等路由元数据。 */
    private <T> SpringAiRoutingResult<T> invokeStreaming(
            String providerName,
            String analysisId,
            String conversationId,
            List<Message> messages,
            Class<T> outputType,
            boolean fallbackUsed,
            Consumer<AiReviewStreamEvent> observer) {
        SpringAiProviderClient provider = registry.find(providerName)
                .orElseThrow(() -> new IllegalStateException(
                        "Spring AI provider is not registered: " + providerName));
        T body = invoker.invokeStreaming(provider, analysisId, conversationId,
                messages, outputType, fallbackUsed, observer);
        return new SpringAiRoutingResult<>(
                body, provider.providerName(), provider.modelName(), fallbackUsed);
    }

    private String requireProviderName(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "ops-assistant.ai." + propertyName + " must not be blank");
        }
        return value.trim();
    }
}
