package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.MetricTrend;
import com.enterprise.opsassistant.domain.RiskLevel;

import java.util.List;
import java.util.Objects;

/**
 * Spring AI 对自然语言告警执行 Structured Output 后得到的候选识别结果。
 *
 * <p>这里特意使用独立的 AI 输出对象，而不让模型直接创建最终的领域对象。原因是模型输出只能
 * 被视为“候选事实”：服务名可能不合法、指标值可能没有出现在原文中，风险等级也可能低估。
 * {@code AlertParserAgent} 会把本对象与 Java 规则基线合并并再次校验，校验通过后才生成
 * {@code AlertRecognition} 交给后续工具规划阶段。</p>
 *
 * @param serviceName 模型从原文理解出的服务标准名；无法判断时必须返回 unknown-service
 * @param alertType 模型归一化后的主要告警类型
 * @param abnormalMetrics 只允许包含原始告警明确给出的指标，不允许根据常识猜测数值
 * @param initialRisk 模型仅根据原始告警判断的初始风险
 * @param userImpact 原文是否明确说明用户、客户或核心交易已经受到影响
 * @param escalationSuggested 是否建议立即升级人工事故响应
 * @param summary 对原始告警的简短中文概括
 */
public record AlertUnderstandingStructuredOutput(
        String serviceName,
        AlertType alertType,
        List<MetricCandidate> abnormalMetrics,
        RiskLevel initialRisk,
        boolean userImpact,
        boolean escalationSuggested,
        String summary) {

    /**
     * 对可能缺失的模型字段提供安全默认值。
     *
     * <p>这里只做空值归一化，不在这里“修正”业务结论；真正的安全合并由 AlertParserAgent 完成，
     * 从而让所有入口遵守同一套领域规则。</p>
     */
    public AlertUnderstandingStructuredOutput {
        serviceName = normalizeText(serviceName, "unknown-service");
        alertType = Objects.requireNonNullElse(alertType, AlertType.UNKNOWN);
        abnormalMetrics = List.copyOf(Objects.requireNonNullElse(abnormalMetrics, List.of()));
        initialRisk = Objects.requireNonNullElse(initialRisk, RiskLevel.LOW);
        summary = normalizeText(summary, "AI 未提供告警摘要");
    }

    /**
     * 模型识别出的一项指标候选。
     *
     * <p>不包含 observedAt，因为语言模型无法知道服务端真正接收告警的时间；该字段会在 Java
     * 接受候选指标时使用当前时间生成。</p>
     */
    public record MetricCandidate(
            String metricName,
            double currentValue,
            String unit,
            Double threshold,
            MetricTrend trend) {

        /** 保留空单位，但拒绝空指标名，并为缺失趋势提供 UNKNOWN。 */
        public MetricCandidate {
            metricName = normalizeText(metricName, "unknownMetric");
            unit = Objects.requireNonNullElse(unit, "").trim();
            trend = Objects.requireNonNullElse(trend, MetricTrend.UNKNOWN);
        }
    }

    private static String normalizeText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
