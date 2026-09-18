package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用 OpenAI 兼容 {@code /chat/completions} 协议调用真实模型的客户端。
 *
 * <p>DeepSeek 和阿里百炼都支持该协议，因此可以共用实现，只通过配置切换 baseUrl、API Key、
 * model、temperature、maxTokens 和 timeout。</p>
 */
public class OpenAiCompatibleChatClient implements AiChatClient {

    private final String providerName;
    private final OpsAssistantAiProperties.Provider provider;
    private final WebClient webClient;

    public OpenAiCompatibleChatClient(String providerName,
                                      OpsAssistantAiProperties.Provider provider,
                                      WebClient.Builder webClientBuilder) {
        this.providerName = providerName;
        this.provider = provider;
        this.webClient = webClientBuilder.baseUrl(trimTrailingSlash(provider.getBaseUrl())).build();
    }

    @Override
    public String providerName() {
        return providerName;
    }

    @Override
    public String modelName() {
        return provider.getModel();
    }

    /** 构造标准请求体、按 Provider 超时等待响应，并提取第一条 assistant 内容。 */
    @Override
    public AiChatResponse chat(AiChatRequest request) {
        if (!StringUtils.hasText(provider.getApiKey())) {
            throw new IllegalStateException("provider " + providerName + " is missing API key");
        }
        if (!StringUtils.hasText(provider.getBaseUrl())) {
            throw new IllegalStateException("provider " + providerName + " is missing base URL");
        }

        long startedAt = System.nanoTime();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", provider.getModel());
        body.put("temperature", provider.getTemperature());
        body.put("max_tokens", provider.getMaxTokens());
        body.put("messages", request.messages().stream()
                .map(message -> Map.of(
                        "role", message.role().apiValue(),
                        "content", message.content()))
                .toList());

        JsonNode response = webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + provider.getApiKey())
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(provider.getTimeout());

        String content = extractContent(response);
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new AiChatResponse(content, providerName, provider.getModel(), latencyMs);
    }

    /** 严格校验响应结构，空 choices 或空 content 都触发主备降级。 */
    private String extractContent(JsonNode response) {
        if (response == null) {
            throw new IllegalStateException("AI provider returned an empty response");
        }
        JsonNode choices = response.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IllegalStateException("AI provider returned no choices");
        }
        String content = choices.get(0).path("message").path("content").asText();
        if (!StringUtils.hasText(content)) {
            List<String> candidates = choices.findValuesAsText("content");
            if (!candidates.isEmpty() && StringUtils.hasText(candidates.get(0))) {
                return candidates.get(0);
            }
            throw new IllegalStateException("AI provider returned blank content");
        }
        return content;
    }

    /** WebClient baseUrl 不保留结尾斜杠，避免拼接路径时出现双斜杠。 */
    private static String trimTrailingSlash(String value) {
        String normalized = value == null ? "" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
