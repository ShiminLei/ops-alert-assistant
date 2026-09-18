package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.ai.AlertUnderstandingAiService;
import com.enterprise.opsassistant.ai.AlertUnderstandingStructuredOutput;
import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.MetricTrend;
import com.enterprise.opsassistant.domain.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /**
     * AI 可以补充规则词典尚未认识的服务名和语义结论，但伪造的指标会被 Java 拒绝。
     * 该用例同时证明 Agent 使用的是 AI Service 返回值，而不是仍然只运行旧正则。
     */
    @Test
    void shouldMergeAiUnderstandingThroughJavaSafetyBoundary() {
        AlertUnderstandingAiService aiService = mock(AlertUnderstandingAiService.class);
        when(aiService.understand(
                "conversation-ai-parser",
                "结算核心调用第三方认证接口失败，用户无法支付"))
                .thenReturn(Optional.of(new AlertUnderstandingStructuredOutput(
                        "payment-service",
                        AlertType.DEPENDENCY_FAILURE,
                        List.of(new AlertUnderstandingStructuredOutput.MetricCandidate(
                                "errorRate", 99.0, "%", 5.0, MetricTrend.UP)),
                        RiskLevel.CRITICAL,
                        true,
                        true,
                        "支付依赖失败已经影响用户支付"
                )));

        AlertParserAgent aiAgent = new AlertParserAgent(aiService);
        var result = aiAgent.parse(
                "结算核心调用第三方认证接口失败，用户无法支付",
                "conversation-ai-parser");

        assertThat(result.serviceName()).isEqualTo("payment-service");
        assertThat(result.alertType()).isEqualTo(AlertType.DEPENDENCY_FAILURE);
        assertThat(result.initialRisk()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.userImpact()).isTrue();
        assertThat(result.summary()).isEqualTo("支付依赖失败已经影响用户支付");
        assertThat(result.abnormalMetrics())
                .as("原文没有 99，AI 不能凭空添加 99%% 错误率")
                .isEmpty();
    }

    /** 即使名称格式正确，未登记在服务目录中的 AI 服务名也必须被拒绝。 */
    @Test
    void shouldRejectAiServiceNameThatIsNotRegistered() {
        AlertUnderstandingAiService aiService = mock(AlertUnderstandingAiService.class);
        when(aiService.understand(
                "conversation-unknown-service",
                "某个内部组件响应异常"))
                .thenReturn(Optional.of(new AlertUnderstandingStructuredOutput(
                        "invented-service",
                        AlertType.API_TIMEOUT,
                        List.of(),
                        RiskLevel.MEDIUM,
                        false,
                        false,
                        "内部组件响应异常"
                )));

        AlertParserAgent aiAgent = new AlertParserAgent(aiService);
        var result = aiAgent.parse(
                "某个内部组件响应异常",
                "conversation-unknown-service");

        assertThat(result.serviceName()).isEqualTo("unknown-service");
        assertThat(result.alertType()).isEqualTo(AlertType.API_TIMEOUT);
    }

    /** 主备模型不可用时 AI Service 返回空结果，Agent 必须完整保留 Java 规则基线。 */
    @Test
    void shouldKeepRuleBaselineWhenAiUnderstandingIsUnavailable() {
        AlertUnderstandingAiService aiService = mock(AlertUnderstandingAiService.class);
        when(aiService.understand(
                "conversation-rule-fallback",
                "订单服务 CPU 达到 96%"))
                .thenReturn(Optional.empty());

        AlertParserAgent aiAgent = new AlertParserAgent(aiService);
        var result = aiAgent.parse(
                "订单服务 CPU 达到 96%",
                "conversation-rule-fallback");

        assertThat(result.serviceName()).isEqualTo("order-service");
        assertThat(result.alertType()).isEqualTo(AlertType.HIGH_CPU);
        assertThat(result.initialRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.abnormalMetrics()).singleElement()
                .satisfies(metric -> assertThat(metric.currentValue()).isEqualTo(96.0));
    }
}
