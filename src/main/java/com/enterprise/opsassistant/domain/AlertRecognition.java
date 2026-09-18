package com.enterprise.opsassistant.domain;

import java.util.List;
import java.util.Objects;

/**
 * 告警识别阶段的结构化输出。
 *
 * <p>用户输入通常是一段不规范的自然语言，例如“支付接口刚发布后一直超时”。
 * AlertParserAgent 会把它转换为本对象，使后续工具规划、证据收集和根因分析都使用稳定字段，
 * 而不需要反复解析原始文本。</p>
 *
 * @param serviceName 告警涉及的服务名，是后续查询各个 Mock 运维工具的关键参数
 * @param alertType 归一化后的告警类型；无法可靠识别时使用 {@link AlertType#UNKNOWN}
 * @param abnormalMetrics 从告警文本中直接提取到的异常指标；尚未提取到时为空列表
 * @param initialRisk 仅根据原始告警判断的初始风险，收集证据后还会计算最终风险
 * @param userImpact 当前是否已经观察到用户影响
 * @param escalationSuggested 识别阶段是否建议立即升级给人工或更高级别值班人员
 * @param summary 对原始告警的简短结构化概括
 */
public record AlertRecognition(
        String serviceName,
        AlertType alertType,
        List<MetricObservation> abnormalMetrics,
        RiskLevel initialRisk,
        boolean userImpact,
        boolean escalationSuggested,
        String summary) {

    /**
     * 规范化并校验识别结果。
     *
     * <p>record 的紧凑构造器会在对象创建时统一建立数据边界：必填字段拒绝空值，
     * 可选枚举提供安全默认值，集合通过 {@link List#copyOf(java.util.Collection)} 变成不可变快照。
     * 这样即使调用方随后修改原列表，也不会偷偷改变一次已经完成的告警识别结果。</p>
     */
    public AlertRecognition {
        serviceName = requireText(serviceName, "serviceName");
        alertType = Objects.requireNonNullElse(alertType, AlertType.UNKNOWN);
        abnormalMetrics = List.copyOf(Objects.requireNonNullElse(abnormalMetrics, List.of()));
        initialRisk = Objects.requireNonNullElse(initialRisk, RiskLevel.LOW);
        summary = Objects.requireNonNullElse(summary, "").trim();
    }

    /** 校验业务必填文本，并去掉用户输入首尾可能存在的空白。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
