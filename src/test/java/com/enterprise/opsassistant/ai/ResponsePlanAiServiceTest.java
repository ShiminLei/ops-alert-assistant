package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.config.OpsAssistantAiProperties;
import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.EvidenceCollectionResult;
import com.enterprise.opsassistant.domain.EvidenceStatus;
import com.enterprise.opsassistant.domain.RiskLevel;
import com.enterprise.opsassistant.domain.RootCauseAssessment;
import com.enterprise.opsassistant.domain.RootCauseCandidate;
import com.enterprise.opsassistant.domain.ToolEvidence;
import com.enterprise.opsassistant.domain.ToolPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证处置规划阶段真实经过 Spring AI 主备路由和 Structured Output。 */
class ResponsePlanAiServiceTest {

    @Test
    void shouldCallSpringAiAndReturnEvidenceBackedAction() {
        OpsAssistantAiProperties properties = new OpsAssistantAiProperties();
        properties.setPrimaryProvider("primary");
        properties.setBackupProvider("backup");
        SpringAiClientRegistry registry = new SpringAiClientRegistry(List.of(
                provider("primary", "primary-model"),
                provider("backup", "backup-model")
        ));
        ResponsePlanAiService service = new ResponsePlanAiService(
                new SpringAiModelRouter(properties, registry),
                new ObjectMapper().findAndRegisterModules());
        AlertRecognition recognition = new AlertRecognition(
                "payment-service", AlertType.POST_DEPLOYMENT_FAILURE,
                List.of(), RiskLevel.HIGH, true, true, "支付发布后失败");
        EvidenceCollectionResult collection = evidenceCollection();
        RootCauseAssessment assessment = new RootCauseAssessment(
                RiskLevel.HIGH,
                List.of(new RootCauseCandidate("发布变更引发异常", 0.8, List.of("ev-service"))),
                List.of("发布与故障时间相关"), true, true, "用户支付失败");

        var result = service.plan(
                "conversation-response-plan", recognition, assessment, collection);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().actions()).hasSize(1);
        assertThat(result.orElseThrow().actions().get(0).type())
                .isEqualTo(ResponsePlanStructuredOutput.ActionType.INVESTIGATE);
        assertThat(result.orElseThrow().actions().get(0).evidenceIds())
                .containsExactly("ev-service");
    }

    /** 创建与三个计划工具一一对应的成功证据。 */
    private EvidenceCollectionResult evidenceCollection() {
        ToolPlan plan = new ToolPlan(
                "payment-service", AlertType.POST_DEPLOYMENT_FAILURE,
                List.of("service-status", "error-log", "resource-usage"), "测试处置规划");
        List<ToolEvidence> evidence = List.of(
                evidence("ev-service", "service-status"),
                evidence("ev-log", "error-log"),
                evidence("ev-resource", "resource-usage")
        );
        return new EvidenceCollectionResult(plan, evidence, 3, 0, 0);
    }

    /** 创建最小但满足领域约束的工具证据。 */
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
