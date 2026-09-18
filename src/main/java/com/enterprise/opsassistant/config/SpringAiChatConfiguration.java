package com.enterprise.opsassistant.config;

import com.enterprise.opsassistant.ai.DeterministicOpsChatModel;
import com.enterprise.opsassistant.ai.ConversationHistoryAdvisor;
import com.enterprise.opsassistant.ai.SpringAiClientRegistry;
import com.enterprise.opsassistant.ai.SpringAiProviderClient;
import com.enterprise.opsassistant.tool.SpringAiOperationsTools;
import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientBuilderConfigurer;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 企业运维助手的 Spring AI 原生模型配置。
 *
 * <p>这里根据 {@code ops-assistant.ai.providers} 为每个启用的 Provider 创建一个独立
 * {@link ChatClient}。独立客户端非常重要：DeepSeek 和百炼可以拥有不同的地址、密钥、模型、
 * 超时时间及观测数据，业务路由器也才能在主模型失败后明确切换到备用模型。</p>
 *
 * <p>配置层只负责“如何连接模型”，不决定“先调用谁、失败后调用谁”。主备顺序属于业务策略，
 * 由 SpringAiModelRouter 处理，避免网络配置和业务规则混在同一个类里。</p>
 */
@Configuration
public class SpringAiChatConfiguration {

    /**
     * 为所有已启用 Provider 创建客户端并放入注册表。
     *
     * <p>名称以 {@code mock} 开头的 Provider 使用确定性内存模型，方便本地开发和自动化测试；
     * 其他 Provider 使用 Spring AI 的 {@link OpenAiChatModel}。DeepSeek 与百炼都提供 OpenAI
     * 兼容接口，因此无需为每家供应商重新编写 HTTP、JSON 和 Structured Output 代码。</p>
     */
    @Bean
    public SpringAiClientRegistry springAiClientRegistry(
            OpsAssistantAiProperties properties,
            @Qualifier("opsReviewSystemPrompt") Resource systemPrompt,
            ChatClientBuilderConfigurer builderConfigurer,
            SpringAiOperationsTools operationsTools,
            ConversationHistoryAdvisor conversationHistoryAdvisor,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ResponseErrorHandler responseErrorHandler) {
        List<SpringAiProviderClient> clients = new ArrayList<>();

        for (Map.Entry<String, OpsAssistantAiProperties.Provider> entry
                : properties.getProviders().entrySet()) {
            String providerName = requireText(entry.getKey(), "provider name");
            OpsAssistantAiProperties.Provider provider = entry.getValue();
            if (provider == null || !provider.isEnabled()) {
                continue;
            }

            String modelName = requireText(
                    provider.getModel(), "model of provider " + providerName);
            ChatModel model = isMockProvider(providerName)
                    ? new DeterministicOpsChatModel()
                    : createRemoteModel(providerName, provider, toolCallingManager,
                            observationRegistry, responseErrorHandler);

            // 每个 Provider 使用相同的安全系统提示词，但各自持有独立模型连接。
            ChatClient chatClient = builderConfigurer.configure(ChatClient.builder(model))
                    .defaultSystem(systemPrompt)
                    .defaultAdvisors(conversationHistoryAdvisor)
                    .defaultTools(operationsTools)
                    .build();
            clients.add(new SpringAiProviderClient(providerName, modelName, chatClient));
        }

        if (clients.isEmpty()) {
            throw new IllegalStateException("At least one Spring AI provider must be enabled");
        }
        return new SpringAiClientRegistry(clients);
    }

    /**
     * 创建一个远程 OpenAI 兼容模型。
     *
     * <p>Spring AI 仍然负责请求协议、响应解析、工具调用、结构化输出和 Micrometer 观测。
     * 本项目外层已经使用 Resilience4j 做 Provider 级重试，所以模型内部重试固定为一次，避免
     * “内层重试 × 外层重试”导致实际请求次数成倍增加。</p>
     */
    private ChatModel createRemoteModel(String providerName,
                                        OpsAssistantAiProperties.Provider provider,
                                        ToolCallingManager toolCallingManager,
                                        ObservationRegistry observationRegistry,
                                        ResponseErrorHandler responseErrorHandler) {
        String baseUrl = trimTrailingSlash(requireText(
                provider.getBaseUrl(), "base-url of provider " + providerName));
        String apiKey = requireText(
                provider.getApiKey(), "api-key of provider " + providerName);
        Duration timeout = requirePositiveTimeout(providerName, provider.getTimeout());

        java.net.http.HttpClient jdkHttpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(jdkHttpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(requestFactory);

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, safeTimeoutMillis(timeout))
                .responseTimeout(timeout);
        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .responseErrorHandler(responseErrorHandler)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(provider.getModel().trim())
                .temperature(provider.getTemperature())
                .maxTokens(provider.getMaxTokens())
                // 明确启用 Spring AI 的完整工具生命周期：模型选工具、Java 执行、结果回传模型。
                .internalToolExecutionEnabled(true)
                .build();

        RetryTemplate noNestedRetry = RetryTemplate.builder()
                .maxAttempts(1)
                .fixedBackoff(1)
                .build();

        return OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .toolCallingManager(toolCallingManager)
                .retryTemplate(noNestedRetry)
                .observationRegistry(observationRegistry)
                .build();
    }

    /**
     * 创建 Spring AI 原生的滑动窗口会话记忆。
     *
     * <p>窗口大小继续沿用项目配置；当消息超过上限时，Spring AI 会淘汰最旧消息。所有主备
     * ChatClient 共享这个实例，所以同一个 conversationId 切换模型后仍能看到一致历史。</p>
     */
    @Bean
    public ChatMemory opsChatMemory(OpsAssistantAiProperties properties) {
        int configuredSize = properties.getMaxHistoryMessages();
        // 每轮固定提交 USER/ASSISTANT 两条消息，偶数窗口避免裁剪后只留下半轮对话。
        int evenWindowSize = configuredSize - (configuredSize % 2);
        return MessageWindowChatMemory.builder()
                .maxMessages(evenWindowSize)
                .build();
    }

    /** 创建“只读历史、成功后由业务提交”的主备安全记忆 Advisor。 */
    @Bean
    public ConversationHistoryAdvisor conversationHistoryAdvisor(ChatMemory chatMemory) {
        return new ConversationHistoryAdvisor(chatMemory);
    }

    /** 系统提示词单独作为资源 Bean，便于以后按 AI Service 场景拆分和版本管理。 */
    @Bean("opsReviewSystemPrompt")
    public Resource opsReviewSystemPrompt() {
        return new ClassPathResource("prompts/ops-review-system.st");
    }

    private boolean isMockProvider(String providerName) {
        return providerName.toLowerCase(Locale.ROOT).startsWith("mock");
    }

    private String requireText(String value, String fieldDescription) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(fieldDescription + " must not be blank");
        }
        return value.trim();
    }

    private Duration requirePositiveTimeout(String providerName, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException(
                    "timeout of provider " + providerName + " must be positive");
        }
        return timeout;
    }

    private int safeTimeoutMillis(Duration timeout) {
        return (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
    }

    private String trimTrailingSlash(String baseUrl) {
        int end = baseUrl.length();
        while (end > 0 && baseUrl.charAt(end - 1) == '/') {
            end--;
        }
        return baseUrl.substring(0, end);
    }
}
