package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.MetricObservation;
import com.enterprise.opsassistant.domain.MetricTrend;
import com.enterprise.opsassistant.domain.RiskLevel;
import com.enterprise.opsassistant.exception.InvalidAlertException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把自然语言告警转换成 {@link AlertRecognition} 的规则型解析 Agent。
 *
 * <p>用户不会严格按照 JSON 模板描述事故，常见输入可能是“支付刚上线新版本后大量超时，
 * 错误率 18.7%”。本类负责完成第一轮确定性解析：识别服务、故障类型、显式指标、风险和
 * 用户影响。后续接入真正的 AI Service 后，模型可以覆盖更多表达方式，但该规则层仍作为
 * 无模型环境和模型输出异常时的安全兜底。</p>
 *
 * <p>这里故意不查询任何运维工具。告警解析只理解用户提供的信息，不能提前把 Mock 数据
 * 当作用户输入，否则会造成“尚未调查就已经知道根因”的数据泄漏。</p>
 */
@Component
public class AlertParserAgent {

    /** 匹配英文服务标准名，例如 payment-service 或 risk-engine-service。 */
    private static final Pattern ENGLISH_SERVICE_PATTERN = Pattern.compile(
            "\\b([a-z][a-z0-9-]{1,62}-service)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 指标正则允许“CPU 90%”“CPU使用率：90%”等常见写法。
     * 捕获组 1 始终是数值，便于统一转换为 double。
     */
    private static final Pattern CPU_PATTERN = percentagePattern("(?:cpu|处理器)(?:使用率|利用率)?");
    private static final Pattern MEMORY_PATTERN = percentagePattern("(?:内存|memory)(?:使用率|利用率)?");
    private static final Pattern ERROR_RATE_PATTERN = percentagePattern("(?:错误率|失败率|error\\s*rate)");
    private static final Pattern P99_PATTERN = Pattern.compile(
            "(?:p99|99分位)(?:响应时间|延迟|耗时)?\\s*(?:为|达到|升至|[:：=])?\\s*(\\d+(?:\\.\\d+)?)\\s*(ms|毫秒|s|秒)",
            Pattern.CASE_INSENSITIVE
    );

    /** 中文口语服务名到系统标准服务名的映射，按插入顺序匹配更明确的别名。 */
    private static final Map<String, String> SERVICE_ALIASES = serviceAliases();

    /**
     * 解析一段原始告警文本。
     *
     * @param rawAlert 用户输入的自然语言告警
     * @return 可直接交给工具规划 Agent 的结构化识别结果
     * @throws InvalidAlertException 输入为空时抛出，避免生成没有审计价值的分析任务
     */
    public AlertRecognition parse(String rawAlert) {
        String alert = normalize(rawAlert);
        String lowerCaseAlert = alert.toLowerCase(Locale.ROOT);
        String serviceName = recognizeService(lowerCaseAlert);
        AlertType alertType = recognizeAlertType(lowerCaseAlert);
        List<MetricObservation> metrics = recognizeMetrics(lowerCaseAlert);
        boolean userImpact = recognizeUserImpact(lowerCaseAlert);
        RiskLevel initialRisk = calculateInitialRisk(lowerCaseAlert, alertType, metrics, userImpact);
        boolean escalationSuggested = shouldEscalate(lowerCaseAlert, initialRisk, userImpact);

        String summary = buildSummary(serviceName, alertType, initialRisk, metrics.size(), userImpact);
        return new AlertRecognition(
                serviceName,
                alertType,
                metrics,
                initialRisk,
                userImpact,
                escalationSuggested,
                summary
        );
    }

    /** 去掉首尾空白并合并连续空白，使正则不受换行或多余空格影响。 */
    private String normalize(String rawAlert) {
        if (rawAlert == null || rawAlert.isBlank()) {
            throw new InvalidAlertException("alert text must not be blank");
        }
        return rawAlert.trim().replaceAll("\\s+", " ");
    }

    /**
     * 优先识别英文标准名，其次识别课程示例中的中文别名。
     * 未识别时返回明确的 unknown-service，让后续工具产生 PARTIAL 证据而不是猜测服务。
     */
    private String recognizeService(String alert) {
        Matcher englishService = ENGLISH_SERVICE_PATTERN.matcher(alert);
        if (englishService.find()) {
            return englishService.group(1).toLowerCase(Locale.ROOT);
        }

        return SERVICE_ALIASES.entrySet().stream()
                .filter(entry -> alert.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("unknown-service");
    }

    /**
     * 按业务优先级识别一个主要告警类型。
     *
     * <p>例如“发布后数据库连接超时”同时命中发布、数据库和超时。发布后故障优先级最高，
     * 因为它会让工具规划器主动查询发布记录并评估回滚；数据库和超时线索仍会通过指标、日志
     * 以及后续工具证据保留下来。</p>
     */
    private AlertType recognizeAlertType(String alert) {
        boolean hasFailureSignal = containsAny(alert,
                "异常", "故障", "失败", "超时", "不可用", "报错", "错误", "升高", "飙升");
        if (containsAny(alert, "发布后", "上线后", "变更后", "刚发布", "刚上线") && hasFailureSignal) {
            return AlertType.POST_DEPLOYMENT_FAILURE;
        }
        if (containsAny(alert, "数据库", "连接池", "jdbc", "hikari", "sqltransientconnection")) {
            return AlertType.DATABASE_CONNECTION;
        }
        if (containsAny(alert, "下游", "依赖服务", "第三方接口", "依赖异常", "依赖失败")) {
            return AlertType.DEPENDENCY_FAILURE;
        }
        if (containsAny(alert, "错误率", "失败率", "error rate")) {
            return AlertType.ERROR_RATE_SPIKE;
        }
        if (containsAny(alert, "cpu", "处理器")) {
            return AlertType.HIGH_CPU;
        }
        if (containsAny(alert, "内存", "memory", "oom")) {
            return AlertType.HIGH_MEMORY;
        }
        if (containsAny(alert, "超时", "延迟", "响应慢", "504", "timeout")) {
            return AlertType.API_TIMEOUT;
        }
        return AlertType.UNKNOWN;
    }

    /** 从文本中提取所有明确出现的核心指标；没有写出的指标绝不自行编造。 */
    private List<MetricObservation> recognizeMetrics(String alert) {
        List<MetricObservation> metrics = new ArrayList<>();
        MetricTrend trend = recognizeTrend(alert);
        Instant observedAt = Instant.now();

        addPercentageMetric(metrics, CPU_PATTERN, alert, "cpuUsage", 80.0, trend, observedAt);
        addPercentageMetric(metrics, MEMORY_PATTERN, alert, "memoryUsage", 85.0, trend, observedAt);
        addPercentageMetric(metrics, ERROR_RATE_PATTERN, alert, "errorRate", 5.0, trend, observedAt);
        addLatencyMetric(metrics, alert, trend, observedAt);
        return List.copyOf(metrics);
    }

    /** 提取百分比指标并附上项目内置告警阈值，后续可由配置文件替换。 */
    private void addPercentageMetric(List<MetricObservation> metrics, Pattern pattern, String alert,
                                     String metricName, double threshold, MetricTrend trend,
                                     Instant observedAt) {
        Matcher matcher = pattern.matcher(alert);
        if (matcher.find()) {
            metrics.add(new MetricObservation(
                    metricName,
                    Double.parseDouble(matcher.group(1)),
                    "%",
                    threshold,
                    trend,
                    observedAt
            ));
        }
    }

    /** 提取 P99 延迟，并把秒统一换算成毫秒，确保不同表达使用同一单位比较。 */
    private void addLatencyMetric(List<MetricObservation> metrics, String alert,
                                  MetricTrend trend, Instant observedAt) {
        Matcher matcher = P99_PATTERN.matcher(alert);
        if (!matcher.find()) {
            return;
        }

        double value = Double.parseDouble(matcher.group(1));
        String sourceUnit = matcher.group(2).toLowerCase(Locale.ROOT);
        double milliseconds = sourceUnit.equals("s") || sourceUnit.equals("秒") ? value * 1000 : value;
        metrics.add(new MetricObservation(
                "p99Latency",
                milliseconds,
                "ms",
                1000.0,
                trend,
                observedAt
        ));
    }

    /** 根据“持续上升”“正在恢复”等文本信号判断指标趋势。 */
    private MetricTrend recognizeTrend(String alert) {
        if (containsAny(alert, "持续上升", "快速上升", "飙升", "越来越高", "恶化")) {
            return MetricTrend.UP;
        }
        if (containsAny(alert, "持续下降", "正在下降", "回落", "恢复中", "好转")) {
            return MetricTrend.DOWN;
        }
        if (containsAny(alert, "保持稳定", "基本稳定", "没有变化")) {
            return MetricTrend.STABLE;
        }
        return MetricTrend.UNKNOWN;
    }

    /** 用户、客户或核心交易失败等表达都视为已经存在用户影响。 */
    private boolean recognizeUserImpact(String alert) {
        return containsAny(alert,
                "用户", "客户", "支付失败", "下单失败", "无法支付", "无法下单", "投诉", "交易失败");
    }

    /**
     * 计算仅基于原始告警的初始风险。
     *
     * <p>风险只允许被明确文本和超阈值指标抬高。最终风险还需结合至少三个工具证据，
     * 因此这里不会因为“感觉严重”直接给出无依据的 CRITICAL。</p>
     */
    private RiskLevel calculateInitialRisk(String alert, AlertType alertType,
                                           List<MetricObservation> metrics, boolean userImpact) {
        if (containsAny(alert, "大面积不可用", "全部不可用", "核心业务中断", "全站故障", "数据丢失")) {
            return RiskLevel.CRITICAL;
        }

        boolean severeMetric = metrics.stream().anyMatch(metric ->
                (metric.metricName().equals("errorRate") && metric.currentValue() >= 10)
                        || (metric.metricName().equals("p99Latency") && metric.currentValue() >= 2000)
                        || (metric.metricName().equals("cpuUsage") && metric.currentValue() >= 90)
                        || (metric.metricName().equals("memoryUsage") && metric.currentValue() >= 95));
        if (severeMetric || userImpact || containsAny(alert, "大量", "持续失败", "不可用", "严重")) {
            return RiskLevel.HIGH;
        }

        boolean anyThresholdExceeded = metrics.stream().anyMatch(MetricObservation::exceedsThreshold);
        if (alertType != AlertType.UNKNOWN || anyThresholdExceeded) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    /** 高风险且已有用户影响、或任何关键级风险，都建议立即升级人工响应。 */
    private boolean shouldEscalate(String alert, RiskLevel risk, boolean userImpact) {
        return risk == RiskLevel.CRITICAL
                || (risk.atLeast(RiskLevel.HIGH) && userImpact)
                || containsAny(alert, "立即升级", "需要人工", "紧急处理");
    }

    /** 生成不依赖模型的稳定摘要，便于日志、前端和后续提示词共同使用。 */
    private String buildSummary(String serviceName, AlertType alertType, RiskLevel risk,
                                int metricCount, boolean userImpact) {
        return "识别到服务 " + serviceName
                + "，告警类型 " + alertType
                + "，初始风险 " + risk
                + "，提取指标 " + metricCount + " 项"
                + "，用户影响" + (userImpact ? "已确认" : "尚未确认");
    }

    /** 判断文本是否包含任意一个关键词，集中处理可读性较差的重复 contains 表达式。 */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 创建一个用于解析百分比指标的正则表达式。 */
    private static Pattern percentagePattern(String metricNameExpression) {
        return Pattern.compile(
                metricNameExpression + "\\s*(?:为|达到|升至|[:：=])?\\s*(\\d+(?:\\.\\d+)?)\\s*%",
                Pattern.CASE_INSENSITIVE
        );
    }

    /** 使用 LinkedHashMap 保证“支付服务”等更具体的别名优先于较短说法。 */
    private static Map<String, String> serviceAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("支付服务", "payment-service");
        aliases.put("支付接口", "payment-service");
        aliases.put("支付系统", "payment-service");
        aliases.put("订单服务", "order-service");
        aliases.put("订单系统", "order-service");
        aliases.put("风控服务", "risk-engine");
        aliases.put("库存服务", "inventory-service");
        return Map.copyOf(aliases);
    }
}
