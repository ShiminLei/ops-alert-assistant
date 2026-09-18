package com.enterprise.opsassistant.ai;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 为主备模型安全注入 Spring AI Chat Memory 的 Advisor。
 *
 * <p>Spring AI 标准 MessageChatMemoryAdvisor 会在模型调用前写入当前用户消息。单模型场景这样很
 * 方便，但主模型失败并切换备用模型时，失败请求可能留下半轮会话。这个 Advisor 只读取历史并
 * 加入 Prompt，不在调用前后写入；业务服务会在结构化输出成功后一次性提交 USER/ASSISTANT 两条
 * 消息，从而获得类似事务的“成功才提交”语义。</p>
 */
public final class ConversationHistoryAdvisor implements BaseChatMemoryAdvisor {

    private final ChatMemory chatMemory;

    public ConversationHistoryAdvisor(ChatMemory chatMemory) {
        if (chatMemory == null) {
            throw new IllegalArgumentException("chatMemory must not be null");
        }
        this.chatMemory = chatMemory;
    }

    /**
     * 根据请求上下文中的 conversationId 读取历史，并放到本轮消息之前。
     * 系统消息始终移动到首位，防止历史内容越过系统安全指令。
     */
    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain advisorChain) {
        String conversationId = getConversationId(request.context());
        List<Message> messages = new ArrayList<>(chatMemory.get(conversationId));
        messages.addAll(request.prompt().getInstructions());
        moveSystemMessageToFront(messages);

        return request.mutate()
                .prompt(request.prompt().mutate().messages(messages).build())
                .build();
    }

    /**
     * 保持响应不变。写入动作故意不放在这里，因为 Structured Output 转换发生在 Advisor 之后；
     * 只有上层成功拿到强类型对象，才能确定本轮结果值得进入记忆。
     */
    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain advisorChain) {
        return response;
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
    }

    private void moveSystemMessageToFront(List<Message> messages) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof SystemMessage) {
                Message systemMessage = messages.remove(index);
                messages.add(0, systemMessage);
                return;
            }
        }
    }
}
