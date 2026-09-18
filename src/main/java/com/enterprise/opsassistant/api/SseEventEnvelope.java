package com.enterprise.opsassistant.api;

import java.time.Instant;

/**
 * SSE 连接中所有业务事件共用的统一信封。
 *
 * <p>SSE 协议自身只有 {@code id/event/data} 三类文本字段，并不了解一次事故分析（Run）的
 * 边界。这个信封把传输顺序和业务顺序显式放进 JSON，使浏览器、日志采集器和以后可能增加的
 * 断线恢复逻辑都能使用同一套编号。</p>
 *
 * <p>{@code id} 和 {@code seq} 看似接近，但作用域不同：id 在整条 SSE 连接内唯一递增；seq
 * 只在同一个 runId 内从 0 递增。如果未来一条 SSE 连接复用多个分析任务，不同 Run 可以同时
 * 拥有 seq=0，但它们的 id 仍然不同。</p>
 *
 * @param id 整条 SSE 连接内唯一递增的事件编号，同时写入协议层 {@code id:} 字段
 * @param runId 业务任务编号；在本项目中就是 analysisId
 * @param seq 当前 Run 内所有类型事件共用的顺序号，从 0 开始
 * @param type 业务事件类型，应与 SSE 协议层 {@code event:} 名称保持一致
 * @param data 具体业务载荷，例如分析阶段、AI 增量、最终报告或错误信息
 * @param occurredAt 信封创建时间，用于分析传输顺序和延迟
 * @param <T> 业务载荷类型
 */
public record SseEventEnvelope<T>(
        long id,
        String runId,
        long seq,
        String type,
        T data,
        Instant occurredAt) {

    public SseEventEnvelope {
        if (id < 1) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (seq < 0) {
            throw new IllegalArgumentException("seq must not be negative");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
