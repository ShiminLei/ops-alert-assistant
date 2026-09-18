package com.enterprise.opsassistant.config;

import com.enterprise.opsassistant.ai.AiChatClient;
import com.enterprise.opsassistant.ai.AiClientRegistry;
import com.enterprise.opsassistant.ai.MockAiChatClient;
import com.enterprise.opsassistant.ai.OpenAiCompatibleChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 根据多环境配置创建全部启用的 AI 客户端。 */
@Configuration
public class AiClientConfiguration {

    /**
     * mock-* 提供方使用本地确定性客户端，其余提供方使用 OpenAI 兼容 HTTP 客户端。
     * disabled Provider 不注册，因此无法被主备路由器选中。
     */
    @Bean
    public AiClientRegistry aiClientRegistry(OpsAssistantAiProperties properties,
                                             WebClient.Builder webClientBuilder) {
        List<AiChatClient> clients = new ArrayList<>();
        properties.getProviders().forEach((name, provider) -> {
            if (!provider.isEnabled()) {
                return;
            }
            if (name.toLowerCase(Locale.ROOT).startsWith("mock")) {
                clients.add(new MockAiChatClient(name, provider.getModel()));
            } else {
                clients.add(new OpenAiCompatibleChatClient(name, provider, webClientBuilder));
            }
        });
        return new AiClientRegistry(clients);
    }
}
