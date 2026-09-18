package com.enterprise.opsassistant.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证统一 SSE 信封不会接受无法排序或无法路由的事件。 */
class SseEventEnvelopeTest {

    /** 合法信封应原样保留连接 id、Run seq、事件类型和业务载荷。 */
    @Test
    void shouldKeepTransportAndRunOrderingFields() {
        SseEventEnvelope<Map<String, String>> envelope = new SseEventEnvelope<>(
                7,
                "analysis-001",
                3,
                "tool.completed",
                Map.of("tool", "service-status"),
                Instant.parse("2026-09-19T00:00:00Z"));

        assertThat(envelope.id()).isEqualTo(7);
        assertThat(envelope.runId()).isEqualTo("analysis-001");
        assertThat(envelope.seq()).isEqualTo(3);
        assertThat(envelope.type()).isEqualTo("tool.completed");
        assertThat(envelope.data()).containsEntry("tool", "service-status");
    }

    /** id 必须从 1 开始，而 Run 内 seq 可以合法地从 0 开始。 */
    @Test
    void shouldRejectInvalidConnectionId() {
        assertThatThrownBy(() -> new SseEventEnvelope<>(
                0, "analysis-001", 0, "run.started", Map.of(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("id must be positive");
    }
}
