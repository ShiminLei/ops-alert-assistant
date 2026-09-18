package com.enterprise.opsassistant.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证内存 Chat Memory 的会话隔离、轮次裁剪和主动清理。 */
class ChatMemoryServiceTest {

    /** 不同 conversationId 必须拥有独立的消息队列，不能把一个事故的内容泄漏到另一个事故。 */
    @Test
    void shouldKeepConversationsIsolated() {
        ChatMemoryService memory = new ChatMemoryService(4);

        memory.rememberExchange("conversation-a", "A 告警", "A 结论");
        memory.rememberExchange("conversation-b", "B 告警", "B 结论");

        assertThat(memory.history("conversation-a"))
                .extracting(AiMessage::content)
                .containsExactly("A 告警", "A 结论");
        assertThat(memory.history("conversation-b"))
                .extracting(AiMessage::content)
                .containsExactly("B 告警", "B 结论");
        assertThat(memory.history("unknown-conversation")).isEmpty();
    }

    /** 超过四条上限时应丢弃最旧完整轮次，保留最近两组 USER/ASSISTANT 对话。 */
    @Test
    void shouldTrimOldestCompleteExchange() {
        ChatMemoryService memory = new ChatMemoryService(4);

        memory.rememberExchange("conversation-a", "第一轮用户", "第一轮助手");
        memory.rememberExchange("conversation-a", "第二轮用户", "第二轮助手");
        memory.rememberExchange("conversation-a", "第三轮用户", "第三轮助手");

        assertThat(memory.history("conversation-a"))
                .extracting(AiMessage::role)
                .containsExactly(AiRole.USER, AiRole.ASSISTANT, AiRole.USER, AiRole.ASSISTANT);
        assertThat(memory.history("conversation-a"))
                .extracting(AiMessage::content)
                .containsExactly("第二轮用户", "第二轮助手", "第三轮用户", "第三轮助手");
    }

    /** 清理会话后应立即返回空历史，且配置不能小于一轮对话所需的两条消息。 */
    @Test
    void shouldClearConversationAndRejectInvalidCapacity() {
        ChatMemoryService memory = new ChatMemoryService(2);
        memory.rememberExchange("conversation-a", "告警", "结论");

        memory.clear("conversation-a");

        assertThat(memory.history("conversation-a")).isEmpty();
        assertThatThrownBy(() -> new ChatMemoryService(1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2");
    }
}
