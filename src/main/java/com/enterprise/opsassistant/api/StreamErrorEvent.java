package com.enterprise.opsassistant.api;

import java.time.Instant;
import java.util.Objects;

/**
 * SSE 连接建立之后发送给客户端的安全错误事件。
 *
 * <p>连接一旦以 HTTP 200 和 text/event-stream 建立，就不能再改成 HTTP 400 或 500。
 * 因此后续错误通过名为 {@code error} 的 SSE 事件发送，并随后正常关闭连接。</p>
 *
 * @param analysisId 已知时返回分析编号，便于查询日志
 * @param code 稳定的机器可读错误码
 * @param message 不包含堆栈和内部实现的用户提示
 * @param occurredAt 错误发生时间
 */
public record StreamErrorEvent(
        String analysisId,
        String code,
        String message,
        Instant occurredAt) {

    /** 为错误时间提供默认值，并验证客户端需要读取的字段。 */
    public StreamErrorEvent {
        code = requireText(code, "code");
        message = requireText(message, "message");
        occurredAt = Objects.requireNonNullElseGet(occurredAt, Instant::now);
    }

    /** 校验必填文本。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
