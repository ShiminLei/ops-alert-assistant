package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.MetricTrend;
import com.enterprise.opsassistant.domain.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AlertParserAgent 的规则识别测试，覆盖分类、指标、风险和无效输入。 */
class AlertParserAgentTest {

    private final AlertParserAgent agent = new AlertParserAgent();

    /** 使用不同自然语言表达验证主要告警类型的优先级。 */
    @ParameterizedTest
    @CsvSource({
            "支付服务刚发布后大量请求超时, POST_DEPLOYMENT_FAILURE",
            "payment-service 数据库连接池已经耗尽, DATABASE_CONNECTION",
            "支付接口错误率持续上升, ERROR_RATE_SPIKE",
            "订单服务 CPU 达到 96%, HIGH_CPU",
            "订单服务内存使用率达到 97%, HIGH_MEMORY",
            "支付服务下游依赖异常, DEPENDENCY_FAILURE",
            "payment-service P99延迟达到2450ms, API_TIMEOUT"
    })
    void shouldRecognizeCommonAlertTypes(String alert, AlertType expectedType) {
        assertThat(agent.parse(alert).alertType()).isEqualTo(expectedType);
    }

    /** 验证一次复杂中文告警能同时提取服务、三个指标、趋势、风险和用户影响。 */
    @Test
    void shouldExtractStructuredInformationFromNaturalLanguage() {
        var result = agent.parse(
                "支付服务刚发布后大量请求超时，错误率 18.7%，CPU 92%，P99延迟 2.45s 持续上升，用户支付失败"
        );

        assertThat(result.serviceName()).isEqualTo("payment-service");
        assertThat(result.alertType()).isEqualTo(AlertType.POST_DEPLOYMENT_FAILURE);
        assertThat(result.abnormalMetrics()).hasSize(3);
        assertThat(result.abnormalMetrics()).allMatch(metric -> metric.trend() == MetricTrend.UP);
        assertThat(result.abnormalMetrics())
                .anySatisfy(metric -> {
                    assertThat(metric.metricName()).isEqualTo("p99Latency");
                    assertThat(metric.currentValue()).isEqualTo(2450);
                    assertThat(metric.unit()).isEqualTo("ms");
                });
        assertThat(result.initialRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.userImpact()).isTrue();
        assertThat(result.escalationSuggested()).isTrue();
    }

    /** 信息不足时应明确标记未知，而不是猜测不存在的服务或故障类型。 */
    @Test
    void shouldUseSafeDefaultsWhenInformationIsInsufficient() {
        var result = agent.parse("定时任务产生了一条普通提醒");

        assertThat(result.serviceName()).isEqualTo("unknown-service");
        assertThat(result.alertType()).isEqualTo(AlertType.UNKNOWN);
        assertThat(result.initialRisk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.abnormalMetrics()).isEmpty();
        assertThat(result.userImpact()).isFalse();
    }

    /** 空输入没有审计价值，必须在进入工具规划前被拒绝。 */
    @Test
    void shouldRejectBlankAlert() {
        assertThatThrownBy(() -> agent.parse("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }
}
