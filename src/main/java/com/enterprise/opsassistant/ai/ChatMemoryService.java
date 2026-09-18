package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 按 conversationId 保存最近对话消息的内存 Chat Memory。
 *
 * <p>存储的是给模型继续理解上下文的简要对话，不是事故报告的持久化存档。应用重启后
 * 内存会清空；生产环境如需多实例共享和长期保存，可以在保持本接口语义的前提下替换为 Redis
 * 或数据库实现。</p>
 *
 * <p>外层使用 ConcurrentHashMap 保证多个会话可并发访问；每个会话的队列再单独加锁，
 * 保证同一会话的 USER/ASSISTANT 消息成对写入，不会在并发请求时交叉。</p>
 */
@Service
public class ChatMemoryService {

    private final ConcurrentMap<String, Deque<AiMessage>> conversations = new ConcurrentHashMap<>();
    private final int maxHistoryMessages;

    /** 从 AI 配置中读取每个会话最多保留的历史消息数。 */
    @Autowired
    public ChatMemoryService(OpsAssistantAiProperties properties) {
        this(properties.getMaxHistoryMessages());
    }

    /** 单元测试可用小容量直接验证裁剪行为。 */
    ChatMemoryService(int maxHistoryMessages) {
        if (maxHistoryMessages < 2) {
            throw new IllegalArgumentException("maxHistoryMessages must be at least 2");
        }
        // 每轮包含一条 USER 和一条 ASSISTANT，使用偶数上限避免裁剪后只留下半轮对话。
        this.maxHistoryMessages = maxHistoryMessages - (maxHistoryMessages % 2);
    }

    /**
     * 返回指定会话的不可变快照。
     * 未知会话返回空列表，不会因一次读取创建空会话。
     */
    public List<AiMessage> history(String conversationId) {
        Deque<AiMessage> messages = conversations.get(conversationId);
        if (messages == null) {
            return List.of();
        }
        synchronized (messages) {
            return List.copyOf(new ArrayList<>(messages));
        }
    }

    /** 将一轮用户输入和模型回复原子地追加到会话，然后从最旧消息开始裁剪。 */
    public void rememberExchange(String conversationId, String userContent, String assistantContent) {
        requireText(conversationId, "conversationId");
        AiMessage userMessage = new AiMessage(AiRole.USER, userContent);
        AiMessage assistantMessage = new AiMessage(AiRole.ASSISTANT, assistantContent);
        Deque<AiMessage> messages = conversations.computeIfAbsent(
                conversationId,
                ignored -> new ArrayDeque<>()
        );
        synchronized (messages) {
            messages.addLast(userMessage);
            messages.addLast(assistantMessage);
            while (messages.size() > maxHistoryMessages) {
                messages.removeFirst();
            }
        }
    }

    /** 显式删除一个会话，便于未来实现前端“新建会话”或隐私删除功能。 */
    public void clear(String conversationId) {
        if (conversationId != null) {
            conversations.remove(conversationId);
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
