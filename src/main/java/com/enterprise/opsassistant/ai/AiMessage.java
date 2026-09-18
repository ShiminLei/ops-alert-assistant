package com.enterprise.opsassistant.ai;

import java.util.Objects;

/**
 * 一条与具体模型厂商无关的聊天消息。
 *
 * @param role 消息在对话中的角色
 * @param content 消息正文
 */
public record AiMessage(AiRole role, String content) {

    /** 拒绝没有角色或正文的消息，避免向模型发送无意义上下文。 */
    public AiMessage {
        role = Objects.requireNonNull(role, "role must not be null");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        content = content.trim();
    }
}
