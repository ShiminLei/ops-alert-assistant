package com.enterprise.opsassistant.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次分析执行过程中的阶段事件。
 *
 * <p>SupervisorAgent 每完成一个关键步骤就产生一条事件。当前事件用于调用链测试和日志，
 * 后续 SSE 接口会把同一对象实时推送给前端，避免为了流式返回再复制一套编排逻辑。</p>
 *
 * @param analysisId 本次分析的唯一编号，与最终 IncidentReport 和日志 traceId 一致
 * @param stage 当前到达的分析阶段
 * @param message 面向用户的简短进度说明，不包含内部异常堆栈或敏感数据
 * @param occurredAt 事件发生时间
 */
public record AnalysisProgressEvent(
        String analysisId,
        AnalysisStage stage,
        String message,
        Instant occurredAt) {

    /** 确保流式事件具备完整的关联和展示信息。 */
    public AnalysisProgressEvent {
        analysisId = requireText(analysisId, "analysisId");
        stage = Objects.requireNonNull(stage, "stage must not be null");
        message = requireText(message, "message");
        occurredAt = Objects.requireNonNullElseGet(occurredAt, Instant::now);
    }

    /** 校验事件中的必填文本并去掉首尾空白。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
