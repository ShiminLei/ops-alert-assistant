package com.enterprise.opsassistant.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 某个时间点观察到的一项运维指标。
 *
 * <p>指标不仅保存一个数值，还同时保存单位、阈值、趋势和采集时间。这样模型可以区分
 * “CPU 为 90% 且仍在上升”和“CPU 曾到 90% 但正在恢复”，避免只看孤立数字得出结论。</p>
 *
 * @param metricName 指标的稳定名称，例如 cpuUsage、errorRate 或 p99Latency
 * @param currentValue 当前观测值
 * @param unit 数值单位，例如 %、ms 或 connections；无单位时为空字符串
 * @param threshold 告警阈值；为 null 表示当前没有可比较的阈值
 * @param trend 指标相较前一时间窗口的变化趋势
 * @param observedAt 指标被采集的时间点
 */
public record MetricObservation(
        String metricName,
        double currentValue,
        String unit,
        Double threshold,
        MetricTrend trend,
        Instant observedAt) {

    /** 对可选字段应用默认值，并保证指标名始终有效。 */
    public MetricObservation {
        metricName = requireText(metricName, "metricName");
        unit = Objects.requireNonNullElse(unit, "");
        trend = Objects.requireNonNullElse(trend, MetricTrend.UNKNOWN);
        observedAt = Objects.requireNonNullElseGet(observedAt, Instant::now);
    }

    /**
     * 判断当前指标是否严格超过配置阈值。
     *
     * @return 存在阈值且当前值大于阈值时返回 true；没有阈值时返回 false
     */
    public boolean exceedsThreshold() {
        return threshold != null && currentValue > threshold;
    }

    /** 校验指标名称等必填文本，并移除首尾空白。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
