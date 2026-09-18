package com.enterprise.opsassistant.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一次告警智能处置分析的最终结构化报告。
 *
 * <p>该对象聚合整条链路的输入和输出，是 REST API 返回、Markdown 报告生成、历史记录保存
 * 以及前端展示的统一数据来源。保留模型与降级信息，可以让使用者知道结论由哪个模型生成，
 * 也方便排查主模型失败后备用模型是否正常接管。</p>
 *
 * @param analysisId 本次分析的唯一编号，用于关联日志、指标、会话和报告
 * @param originalAlert 用户提交的原始自然语言告警，便于审计且不能被结构化结果替代
 * @param recognition 告警识别阶段的结构化结果
 * @param evidence 本次实际收集到的全部工具证据，包括失败和部分成功的调用
 * @param rootCause 基于证据形成的最终风险和根因判断
 * @param recommendedActions 按执行顺序排列的处置建议
 * @param followUpMetrics 处置后需要继续观察的指标名称
 * @param modelProvider 实际完成分析的模型提供方
 * @param modelName 实际使用的模型名称
 * @param fallbackUsed 主模型失败后是否使用了备用模型
 * @param generatedAt 报告生成时间
 */
public record IncidentReport(
        String analysisId,
        String originalAlert,
        AlertRecognition recognition,
        List<ToolEvidence> evidence,
        RootCauseAssessment rootCause,
        List<RecommendedAction> recommendedActions,
        List<String> followUpMetrics,
        String modelProvider,
        String modelName,
        boolean fallbackUsed,
        Instant generatedAt) {

    /**
     * 校验完整报告并冻结所有列表。
     *
     * <p>最终报告属于一次分析的历史事实，因此创建后不应再被调用方修改。
     * 对列表做防御性复制能保证后续持久化、前端展示和 Markdown 导出看到的是同一份结果。</p>
     */
    public IncidentReport {
        analysisId = requireText(analysisId, "analysisId");
        originalAlert = requireText(originalAlert, "originalAlert");
        recognition = Objects.requireNonNull(recognition, "recognition must not be null");
        evidence = List.copyOf(Objects.requireNonNullElse(evidence, List.of()));
        rootCause = Objects.requireNonNull(rootCause, "rootCause must not be null");
        recommendedActions = List.copyOf(Objects.requireNonNullElse(recommendedActions, List.of()));
        followUpMetrics = List.copyOf(Objects.requireNonNullElse(followUpMetrics, List.of()));
        modelProvider = requireText(modelProvider, "modelProvider");
        modelName = requireText(modelName, "modelName");
        generatedAt = Objects.requireNonNullElseGet(generatedAt, Instant::now);
    }

    /** 校验最终报告中的必填文本字段，并统一去除首尾空白。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
