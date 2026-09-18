package com.enterprise.opsassistant.config;

import com.enterprise.opsassistant.ai.DeterministicOpsChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;

/**
 * 企业运维助手的 Spring AI 原生组件配置。
 *
 * <p>配置类只负责组装模型与 ChatClient，不放业务分析逻辑。业务服务依赖命名后的
 * {@code opsReviewChatClient}，以后即使增加告警识别、报告生成等多个不同提示词的 ChatClient，
 * 也能清楚知道每个客户端的用途。</p>
 */
@Configuration
public class SpringAiChatConfiguration {

    /**
     * local/test 环境优先使用确定性模型，避免在开发机启动或运行测试时意外请求真实模型。
     *
     * <p>{@link Primary} 用来告诉 Spring：当容器里同时存在自动配置的 OpenAI 模型和本地模型时，
     * ChatClient.Builder 应选择这个本地模型。prod 环境不会创建该 Bean，因此会自然切换到
     * Spring AI 自动配置的 OpenAI 兼容模型。</p>
     */
    @Bean
    @Primary
    @Profile({"local", "test"})
    public ChatModel deterministicOpsChatModel() {
        return new DeterministicOpsChatModel();
    }

    /**
     * 创建事故复核专用 ChatClient，并从 classpath 读取系统提示词。
     *
     * <p>系统提示词作为资源文件管理，比硬编码在 Java 字符串中更便于审核和迭代。这里使用
     * Spring AI 自动配置的 Builder，因此模型观测、重试以及后续 Advisor 定制仍然有效。</p>
     */
    @Bean("opsReviewChatClient")
    public ChatClient opsReviewChatClient(
            ChatClient.Builder builder,
            @Qualifier("opsReviewSystemPrompt") Resource systemPrompt) {
        return builder.defaultSystem(systemPrompt).build();
    }

    /** 将提示词资源单独注册成 Bean，明确它属于事故复核场景。 */
    @Bean("opsReviewSystemPrompt")
    public Resource opsReviewSystemPrompt() {
        return new org.springframework.core.io.ClassPathResource("prompts/ops-review-system.st");
    }
}
