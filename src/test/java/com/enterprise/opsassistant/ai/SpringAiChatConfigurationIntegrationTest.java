package com.enterprise.opsassistant.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证事故复核已经真正经过 Spring AI ChatClient 和 Structured Output。
 *
 * <p>该测试使用 test profile 下的 {@link DeterministicOpsChatModel}，不访问网络、不消耗模型额度。
 * 测试目标不是评估模型智能程度，而是确认提示词调用、模型响应以及 JSON 到 Java record 的转换链路
 * 已经正确接通。若 Structured Output 配置损坏，本测试会直接失败，不允许旧 AI 路由悄悄兜底。</p>
 */
@ActiveProfiles("test")
@SpringBootTest
class SpringAiChatConfigurationIntegrationTest {

    @Autowired
    @Qualifier("opsReviewChatClient")
    private ChatClient chatClient;

    /** Spring AI 应当把确定性模型返回的 JSON 自动转换成强类型复核对象。 */
    @Test
    void shouldConvertModelResponseToStructuredReview() {
        AiReviewStructuredOutput review = chatClient.prompt()
                .user("请复核一条用于集成测试的运维告警。")
                .call()
                .entity(AiReviewStructuredOutput.class);

        assertThat(review).isNotNull();
        assertThat(review.evidenceConsistency()).contains("Spring AI 结构化复核链路");
        assertThat(review.evidenceGaps()).hasSize(1);
        assertThat(review.handlingNotes())
                .contains("最终风险不得低于 Java 规则结果", "所有生产变更必须人工确认");
    }
}
