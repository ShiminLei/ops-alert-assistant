package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import com.enterprise.opsassistant.exception.AiProvidersUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按配置执行“主模型优先、失败后备用模型接管”的路由器。
 *
 * <p>路由器只负责模型可用性降级，不负责业务提示词和根因规则。主模型返回空内容、缺少配置、
 * 网络失败、超时或服务端异常都会进入备用模型；两个模型都失败才抛出聚合异常。</p>
 */
@Component
public class AiModelRouter {

    private static final Logger log = LoggerFactory.getLogger(AiModelRouter.class);

    private final OpsAssistantAiProperties properties;
    private final AiClientRegistry registry;

    public AiModelRouter(OpsAssistantAiProperties properties, AiClientRegistry registry) {
        this.properties = properties;
        this.registry = registry;
    }

    /**
     * 调用主模型，并在失败时自动调用备用模型。
     *
     * @param request 业务层构造的厂商无关聊天请求
     * @return 实际成功响应以及是否发生降级
     */
    public AiRoutingResult chatWithFallback(AiChatRequest request) {
        String primary = requireProviderName(properties.getPrimaryProvider(), "primary-provider");
        String backup = requireProviderName(properties.getBackupProvider(), "backup-provider");

        RuntimeException primaryFailure;
        try {
            AiChatResponse response = invoke(primary, request);
            return new AiRoutingResult(response, false);
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            log.warn("主 AI 模型调用失败，准备切换备用模型: primary={}, reason={}",
                    primary, exception.toString());
        }

        if (primary.equalsIgnoreCase(backup)) {
            throw new AiProvidersUnavailableException(primary, backup, primaryFailure,
                    new IllegalStateException("backup provider must differ from primary provider"));
        }

        try {
            AiChatResponse response = invoke(backup, request);
            log.info("备用 AI 模型接管成功: primary={}, backup={}, model={}",
                    primary, backup, response.model());
            return new AiRoutingResult(response, true);
        } catch (RuntimeException backupFailure) {
            log.error("主备 AI 模型均调用失败: primary={}, backup={}", primary, backup);
            throw new AiProvidersUnavailableException(primary, backup, primaryFailure, backupFailure);
        }
    }

    /** 查找并调用指定客户端；未注册 Provider 也作为可降级故障处理。 */
    private AiChatResponse invoke(String provider, AiChatRequest request) {
        AiChatClient client = registry.find(provider)
                .orElseThrow(() -> new IllegalStateException("AI provider is not registered: " + provider));
        return client.chat(request);
    }

    /** 对缺失主备名称给出明确配置错误，而不是产生难懂的空指针。 */
    private String requireProviderName(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("ops-assistant.ai." + propertyName + " must not be blank");
        }
        return value.trim();
    }
}
