package com.enterprise.opsassistant.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证主备路由专用 Chat Memory Advisor 的历史注入和失败隔离语义。 */
class ConversationHistoryAdvisorTest {

    /** 历史应进入模型 Prompt，但 Advisor 本身不能提前写入当前消息。 */
    @Test
    void shouldInjectHistoryWithoutCommittingCurrentExchange() {
        ChatMemory memory = memoryWithOneExchange();
        CapturingModel model = new CapturingModel(false);
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(new ConversationHistoryAdvisor(memory))
                .build();

        String response = client.prompt()
                .user("当前告警")
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "conversation-a"))
                .call()
                .content();

        assertThat(response).isEqualTo("本轮回复");
        assertThat(model.lastPrompt.getInstructions())
                .extracting(Message::getText)
                .containsExactly("历史告警", "历史结论", "当前告警");
        assertThat(memory.get("conversation-a"))
                .extracting(Message::getText)
                .containsExactly("历史告警", "历史结论");
    }

    /** 模型异常时记忆必须保持原状，备用模型才能读取干净且不重复的历史。 */
    @Test
    void shouldNotPolluteMemoryWhenModelFails() {
        ChatMemory memory = memoryWithOneExchange();
        ChatClient client = ChatClient.builder(new CapturingModel(true))
                .defaultAdvisors(new ConversationHistoryAdvisor(memory))
                .build();

        assertThatThrownBy(() -> client.prompt()
                .user("失败请求")
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "conversation-a"))
                .call()
                .content())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("model unavailable");

        assertThat(memory.get("conversation-a"))
                .extracting(Message::getText)
                .containsExactly("历史告警", "历史结论");
    }

    private ChatMemory memoryWithOneExchange() {
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(4).build();
        memory.add("conversation-a", List.of(
                new UserMessage("历史告警"),
                new AssistantMessage("历史结论")
        ));
        return memory;
    }

    /** 记录收到的 Prompt，并可按测试需要稳定模拟模型故障。 */
    private static final class CapturingModel implements ChatModel {
        private final boolean fail;
        private Prompt lastPrompt;

        private CapturingModel(boolean fail) {
            this.fail = fail;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt;
            if (fail) {
                throw new IllegalStateException("model unavailable");
            }
            return new ChatResponse(List.of(
                    new Generation(new AssistantMessage("本轮回复"))));
        }
    }
}
