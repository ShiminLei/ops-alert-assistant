package com.enterprise.opsassistant.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainModelTest {

    @Test
    void shouldProtectRecognitionFromExternalListChanges() {
        List<MetricObservation> metrics = new ArrayList<>();
        metrics.add(new MetricObservation(
                "error_rate", 18, "%", 5.0, MetricTrend.UP, Instant.now()));

        AlertRecognition recognition = new AlertRecognition(
                "order-service",
                AlertType.ERROR_RATE_SPIKE,
                metrics,
                RiskLevel.HIGH,
                true,
                true,
                "订单服务错误率显著升高");

        metrics.clear();

        assertThat(recognition.abnormalMetrics()).hasSize(1);
        assertThat(recognition.abnormalMetrics().get(0).exceedsThreshold()).isTrue();
    }

    @Test
    void shouldRejectInvalidRootCauseConfidence() {
        assertThatThrownBy(() -> new RootCauseCandidate("新版本缺陷", 1.2, List.of("deployment-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");
    }
}
