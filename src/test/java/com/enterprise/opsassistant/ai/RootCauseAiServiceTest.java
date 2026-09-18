package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.EvidenceCollectionResult;
import com.enterprise.opsassistant.domain.EvidenceStatus;
import com.enterprise.opsassistant.domain.RiskLevel;
import com.enterprise.opsassistant.domain.ToolEvidence;
import com.enterprise.opsassistant.domain.ToolPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证根因综合阶段真实经过 Spring AI、主备路由和 Structured Output。 */
class RootCauseAiServiceTest {

    @Test
    void shouldCallSpringAiAndReturnCandidatesWithRealEvidenceIds() {
        OpsAssistantAiProperties properties = new OpsAssistantAiProperties();
        properties.setPrimaryProvider("primary");
        properties.setBackupProvider("backup");
        SpringAiClientRegistry registry = new SpringAiClientRegistry(List.of(
                provider("primary", "primary-model"),
                provider("backup", "backup-model")
        ));
        RootCauseAiService service = new RootCauseAiService(
                new SpringAiModelRouter(properties, registry),
                new ObjectMapper().findAndRegisterModules());
        AlertRecognition recognition = new AlertRecognition(
                "payment-service", AlertType.POST_DEPLOYMENT_FAILURE,
                List.of(), RiskLevel.HIGH, true, true, "支付发布后失败");
        EvidenceCollectionResult collection = evidenceCollection();

        var result = service.analyze("conversation-root-cause", recognition, collection);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().candidates()).hasSize(1);
        assertThat(result.orElseThrow().candidates().get(0).evidenceIds())
                .containsExactly("ev-service", "ev-log", "ev-resource");
        assertThat(result.orElseThrow().reasoning()).isNotEmpty();
    }

    /** 为测试创建三个真实可引用的证据；数量与工具计划严格一致。 */
    private EvidenceCollectionResult evidenceCollection() {
        ToolPlan plan = new ToolPlan(
                "payment-service",
                AlertType.POST_DEPLOYMENT_FAILURE,
                List.of("service-status", "error-log", "resource-usage"),
                "测试根因结构化输出");
        List<ToolEvidence> evidence = List.of(
                evidence("ev-service", "service-status"),
                evidence("ev-log", "error-log"),
                evidence("ev-resource", "resource-usage")
        );
        return new EvidenceCollectionResult(plan, evidence, 3, 0, 0);
    }

    /** 创建最小但完整的成功证据，避免测试绕过领域构造约束。 */
    private ToolEvidence evidence(String id, String toolName) {
        return new ToolEvidence(
                id, toolName, "payment-service", EvidenceStatus.SUCCESS,
                "测试证据", Map.of("state", "DEGRADED"), null, 1, Instant.now());
    }

    private SpringAiProviderClient provider(String name, String model) {
        return new SpringAiProviderClient(
                name, model, ChatClient.builder(new DeterministicOpsChatModel()).build());
    }
}
