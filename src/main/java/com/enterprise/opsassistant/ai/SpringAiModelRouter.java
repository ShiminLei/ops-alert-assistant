package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import com.enterprise.opsassistant.exception.AiProvidersUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

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
    public <T> SpringAiRoutingResult<T> callWithFallback(List<Message> messages,
                                                         Class<T> outputType) {
        String primaryName = requireProviderName(
                properties.getPrimaryProvider(), "primary-provider");
        String backupName = requireProviderName(
                properties.getBackupProvider(), "backup-provider");

        RuntimeException primaryFailure;
        try {
            return invoke(primaryName, messages, outputType, false);
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
            SpringAiRoutingResult<T> result = invoke(backupName, messages, outputType, true);
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

    private <T> SpringAiRoutingResult<T> invoke(String providerName,
                                                List<Message> messages,
                                                Class<T> outputType,
                                                boolean fallbackUsed) {
        SpringAiProviderClient provider = registry.find(providerName)
                .orElseThrow(() -> new IllegalStateException(
                        "Spring AI provider is not registered: " + providerName));
        T body = invoker.invoke(provider, messages, outputType);
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
