package com.enterprise.opsassistant.observability;

import com.enterprise.opsassistant.ai.AiReviewResult;
import com.enterprise.opsassistant.domain.RiskLevel;
import com.enterprise.opsassistant.domain.ToolEvidence;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * 企业运维告警助手的统一业务指标入口。
 *
 * <p>业务代码只负责报告“发生了什么”，指标名称、标签和计时单位都在这里集中管理。
 * Micrometer 会把这些指标同时提供给 Actuator metrics 端点和 Prometheus 抓取端点。</p>
 *
 * <p>指标标签必须是取值有限的低基数数据。因此这里不使用 analysisId、原始告警文本或
 * 异常消息作为标签，避免 Prometheus 为每次请求创建新时间序列并持续占用内存。</p>
 */
@Component
public class OpsAssistantMetrics {

    private final MeterRegistry meterRegistry;

    public OpsAssistantMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    /**
     * 创建独立的内存指标容器，供不启动 Spring 的纯单元测试构造业务类。
     * 它不会把数据发送到外部系统。
     */
    public static OpsAssistantMetrics noOp() {
        return new OpsAssistantMetrics(new SimpleMeterRegistry());
    }

    /** 记录一次成功告警分析的数量、风险、AI 模式和端到端耗时。 */
    public void recordAnalysisSuccess(Duration duration,
                                      RiskLevel riskLevel,
                                      AiReviewResult aiReview) {
        String provider = aiReview.ruleOnly() ? "rule-engine" : aiReview.provider();
        String fallback = Boolean.toString(aiReview.fallbackUsed());
        Counter.builder("ops.assistant.analysis")
                .description("告警分析总数")
                .tags("outcome", "success",
                        "risk", riskLevel.name().toLowerCase(Locale.ROOT),
                        "ai_provider", provider,
                        "fallback", fallback,
                        "failure_type", "none")
                .register(meterRegistry)
                .increment();
        recordAnalysisDuration(duration, "success");
    }

    /**
     * 记录一次失败分析。failureType 由调用方传入有限分类，而不是变化不可控的异常消息。
     */
    public void recordAnalysisFailure(Duration duration, String failureType) {
        Counter.builder("ops.assistant.analysis")
                .description("告警分析总数")
                .tags("outcome", "failure",
                        "risk", "unknown",
                        "ai_provider", "unknown",
                        "fallback", "false",
                        "failure_type", failureType)
                .register(meterRegistry)
                .increment();
        recordAnalysisDuration(duration, "failure");
    }

    /** 记录一次运维工具调用，按工具名和证据状态分类。 */
    public void recordToolCall(ToolEvidence evidence) {
        String status = evidence.status().name().toLowerCase(Locale.ROOT);
        Counter.builder("ops.assistant.tool.calls")
                .description("运维工具调用总数")
                .tags("tool", evidence.toolName(), "status", status)
                .register(meterRegistry)
                .increment();
        Timer.builder("ops.assistant.tool.duration")
                .description("运维工具调用耗时")
                .tags("tool", evidence.toolName(), "status", status)
                .register(meterRegistry)
                .record(Duration.ofMillis(evidence.durationMs()));
    }

    /** 记录一次 Provider 级 AI 调用；耗时包含该 Provider 内部可能发生的重试等待。 */
    public void recordAiProviderCall(String provider, String outcome, Duration duration) {
        Counter.builder("ops.assistant.ai.provider.calls")
                .description("AI Provider 调用总数")
                .tags("provider", provider, "outcome", outcome)
                .register(meterRegistry)
                .increment();
        Timer.builder("ops.assistant.ai.provider.duration")
                .description("AI Provider 调用耗时，包含重试")
                .tags("provider", provider, "outcome", outcome)
                .register(meterRegistry)
                .record(duration);
    }

    /** 分析耗时只按成败分类，避免与其他标签组合产生过多时间序列。 */
    private void recordAnalysisDuration(Duration duration, String outcome) {
        Timer.builder("ops.assistant.analysis.duration")
                .description("告警分析端到端耗时")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(duration);
    }
}
