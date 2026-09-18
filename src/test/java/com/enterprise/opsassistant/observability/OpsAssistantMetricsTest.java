package com.enterprise.opsassistant.observability;

import com.enterprise.opsassistant.ai.AiReviewResult;
import com.enterprise.opsassistant.domain.EvidenceStatus;
import com.enterprise.opsassistant.domain.RiskLevel;
import com.enterprise.opsassistant.domain.ToolEvidence;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证业务事件能转换成标签稳定的 Micrometer 计数器和计时器。 */
class OpsAssistantMetricsTest {

    /** 分析、工具和 AI Provider 三类指标应同时保存数量、结果标签与耗时。 */
    @Test
    void shouldRecordBusinessCountersAndTimers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OpsAssistantMetrics metrics = new OpsAssistantMetrics(registry);
        AiReviewResult aiReview = new AiReviewResult(
                "证据一致",
                "mock-primary",
                "mock-ops-primary",
                false,
                false
        );
        ToolEvidence evidence = new ToolEvidence(
                "evidence-1",
                "error-log",
                "payment-service",
                EvidenceStatus.SUCCESS,
                "已找到关键错误",
                Map.of("errorRate", 18.7),
                null,
                25,
                Instant.now()
        );

        metrics.recordAnalysisSuccess(Duration.ofMillis(100), RiskLevel.HIGH, aiReview);
        metrics.recordToolCall(evidence);
        metrics.recordAiProviderCall("mock-primary", "success", Duration.ofMillis(40));

        assertThat(registry.get("ops.assistant.analysis")
                .tags("outcome", "success", "risk", "high",
                        "ai_provider", "mock-primary", "fallback", "false",
                        "failure_type", "none")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ops.assistant.analysis.duration")
                .tag("outcome", "success").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(100.0);
        assertThat(registry.get("ops.assistant.tool.calls")
                .tags("tool", "error-log", "status", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ops.assistant.tool.duration")
                .tags("tool", "error-log", "status", "success")
                .timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(25.0);
        assertThat(registry.get("ops.assistant.ai.provider.calls")
                .tags("provider", "mock-primary", "outcome", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ops.assistant.ai.provider.duration")
                .tags("provider", "mock-primary", "outcome", "success")
                .timer().count()).isEqualTo(1L);
    }

    /** 失败指标只使用可控的错误分类，不把异常消息或请求编号当作标签。 */
    @Test
    void shouldRecordAnalysisFailureWithBoundedLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OpsAssistantMetrics metrics = new OpsAssistantMetrics(registry);

        metrics.recordAnalysisFailure(Duration.ofMillis(5), "invalid_alert");

        assertThat(registry.get("ops.assistant.analysis")
                .tags("outcome", "failure", "risk", "unknown",
                        "ai_provider", "unknown", "fallback", "false",
                        "failure_type", "invalid_alert")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ops.assistant.analysis.duration")
                .tag("outcome", "failure").timer().count()).isEqualTo(1L);
    }
}
