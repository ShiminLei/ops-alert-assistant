package com.enterprise.opsassistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RiskLevelTest {

    @Test
    void shouldCompareRiskLevelsInBusinessOrder() {
        assertThat(RiskLevel.CRITICAL.atLeast(RiskLevel.HIGH)).isTrue();
        assertThat(RiskLevel.MEDIUM.atLeast(RiskLevel.HIGH)).isFalse();
        assertThat(RiskLevel.max(RiskLevel.MEDIUM, RiskLevel.HIGH)).isEqualTo(RiskLevel.HIGH);
    }
}
