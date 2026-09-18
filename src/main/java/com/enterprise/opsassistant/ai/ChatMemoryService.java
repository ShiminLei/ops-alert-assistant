package com.enterprise.opsassistant.ai;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 按 conversationId 保存最近对话消息的内存 Chat Memory。
 *
 * <p>存储的是给模型继续理解上下文的简要对话，不是事故报告的持久化存档。应用重启后
 * 内存会清空；生产环境如需多实例共享和长期保存，可以在保持本接口语义的前提下替换为 Redis
 * 或数据库实现。</p>
 *
 * <p>底层使用 Spring AI {@link MessageWindowChatMemory}，统一管理消息角色、会话隔离与窗口
 * 裁剪。这个 Service 只保留业务需要的“成功后成对提交”和“清除会话”语义，使业务层无需了解
 * ChatMemory 的具体存储实现。</p>
 */
@Service
public class ChatMemoryService {

    private final ChatMemory chatMemory;

    /** Spring 运行时注入全局共享的 Spring AI ChatMemory。 */
    @Autowired
    public ChatMemoryService(ChatMemory chatMemory) {
        if (chatMemory == null) {
            throw new IllegalArgumentException("chatMemory must not be null");
        }
        this.chatMemory = chatMemory;
    }

    /** 单元测试可用小容量创建独立 MessageWindowChatMemory，避免会话状态相互影响。 */
    ChatMemoryService(int maxHistoryMessages) {
        if (maxHistoryMessages < 2) {
            throw new IllegalArgumentException("maxHistoryMessages must be at least 2");
        }
        // 每轮包含一条 USER 和一条 ASSISTANT，使用偶数上限避免裁剪后只留下半轮对话。
        int evenWindowSize = maxHistoryMessages - (maxHistoryMessages % 2);
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(evenWindowSize)
                .build();
    }

    /**
     * 返回指定会话的不可变快照。
     * 未知会话返回空列表，不会因一次读取创建空会话。
     */
    public List<Message> history(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        return List.copyOf(chatMemory.get(conversationId.trim()));
    }

    /** 将一轮用户输入和模型回复原子地追加到会话，然后从最旧消息开始裁剪。 */
    public void rememberExchange(String conversationId, String userContent, String assistantContent) {
        requireText(conversationId, "conversationId");
        Message userMessage = new UserMessage(requireText(userContent, "userContent"));
        Message assistantMessage = new AssistantMessage(
                requireText(assistantContent, "assistantContent"));
        chatMemory.add(requireText(conversationId, "conversationId"),
                List.of(userMessage, assistantMessage));
    }

    /** 显式删除一个会话，便于未来实现前端“新建会话”或隐私删除功能。 */
    public void clear(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            chatMemory.clear(conversationId.trim());
        }
    }

    /** 校验内存键等必填文本，避免空键或空消息进入共享容器。 */
    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
