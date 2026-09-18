package com.enterprise.opsassistant.ai;

import java.time.Instant;

/**
 * Spring AI 模型复核过程产生的实时事件。
 *
 * <p>阶段进度事件只说明“程序执行到了哪里”；本事件承载模型真正生成的内容。两者分开建模，
 * 可以让前端同时展示确定性的多 Agent 阶段和非确定性的模型输出，也避免把模型文本误当成系统
 * 状态。</p>
 *
 * @param analysisId 本次事故分析编号，用于关联日志、阶段事件和最终报告
 * @param provider 当前实际调用的模型提供方
 * @param model 当前实际调用的模型名称
 * @param fallbackUsed 是否已经从主模型切换到备用模型
 * @param phase 流式尝试的生命周期阶段
 * @param chunkSequence 当前模型尝试内的片段序号；START 为 0，DELTA 从 1 递增。它不是整个
 *                      分析 Run 的 seq，重试或切换备用模型时会重新从 0 开始
 * @param delta 本次新增文本；START 和 COMPLETE 阶段为空字符串
 * @param occurredAt 事件创建时间
 */
public record AiReviewStreamEvent(
        String analysisId,
        String provider,
        String model,
        boolean fallbackUsed,
        AiReviewStreamPhase phase,
        int chunkSequence,
        String delta,
        Instant occurredAt) {

    public AiReviewStreamEvent {
        if (analysisId == null || analysisId.isBlank()) {
            throw new IllegalArgumentException("analysisId must not be blank");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (phase == null) {
            throw new IllegalArgumentException("phase must not be null");
        }
        if (chunkSequence < 0) {
            throw new IllegalArgumentException("chunkSequence must not be negative");
        }
        delta = delta == null ? "" : delta;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
