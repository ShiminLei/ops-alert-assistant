package com.enterprise.opsassistant.domain;

import java.time.Instant;

/**
 * 分析过程中已经确定、可以提前展示的一块报告数据。
 *
 * <p>该事件与 {@link AnalysisProgressEvent} 的区别是：Progress 只说明程序执行到哪个阶段，
 * Section 携带已经生成的实际业务结果。例如 EVIDENCE 事件包含一条完整工具证据，ACTION 事件
 * 包含一条可展示的处置建议。前端因此不必等完整 IncidentReport 才开始呈现内容。</p>
 *
 * @param analysisId 本次分析编号，也是统一 SSE 信封的 runId
 * @param section 区段类型，决定 SSE event 名称和前端渲染区域
 * @param data 已完成的业务对象；类型由 section 决定
 * @param occurredAt 区段完成时间
 */
public record AnalysisSectionEvent(
        String analysisId,
        AnalysisSectionType section,
        Object data,
        Instant occurredAt) {

    public AnalysisSectionEvent {
        if (analysisId == null || analysisId.isBlank()) {
            throw new IllegalArgumentException("analysisId must not be blank");
        }
        if (section == null) {
            throw new IllegalArgumentException("section must not be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
