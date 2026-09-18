package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.EvidenceCollectionResult;
import com.enterprise.opsassistant.domain.RootCauseAssessment;
import com.enterprise.opsassistant.exception.AiProvidersUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** 使用 Spring AI 根据已校验根因和真实工具证据提出结构化处置建议。 */
@Service
public class ResponsePlanAiService {

    private static final Logger log = LoggerFactory.getLogger(ResponsePlanAiService.class);

    private final SpringAiModelRouter router;
    private final ObjectMapper objectMapper;

    public ResponsePlanAiService(SpringAiModelRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    /**
     * 通过主备模型生成候选方案；任何模型或转换异常都返回空结果，让规则方案完整接管。
     */
    public Optional<ResponsePlanStructuredOutput> plan(
            String conversationId,
            AlertRecognition recognition,
            RootCauseAssessment assessment,
            EvidenceCollectionResult collection) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (recognition == null || assessment == null || collection == null) {
            throw new IllegalArgumentException("response planning context must not be null");
        }

        String recognitionJson;
        String assessmentJson;
        String evidenceJson;
        try {
            recognitionJson = objectMapper.writeValueAsString(recognition);
            assessmentJson = objectMapper.writeValueAsString(assessment);
            evidenceJson = objectMapper.writeValueAsString(collection.evidence());
        } catch (JsonProcessingException exception) {
            log.warn("处置规划上下文序列化失败，退回 Java 规则方案: reason={}", exception.toString());
            return Optional.empty();
        }

        String prompt = """
                [TASK:RESPONSE_PLANNING]
                你是企业运维处置规划 Agent。请根据已经校验的根因和真实工具证据提出建议。

                约束：
                1. 每条动作必须引用输入中真实存在且非 FAILED 的 evidenceId。
                2. 只能使用目标 JSON Schema 中的动作类型、紧急程度和责任角色枚举。
                3. 只能提出建议，不能声称已经执行回滚、扩容、限流、重启或配置变更。
                4. ROLLBACK、SCALE、CONFIG_CHANGE 必须设置 requiresHumanApproval=true。
                5. rollbackRecommended=false 时不得提出 ROLLBACK。
                6. 不得输出 Shell、SQL、删除数据、关闭数据库或绕过审批的命令。
                7. followUpMetrics 只写用于验证恢复情况的指标名称，不得编造当前数值。

                结构化告警：
                %s

                已校验根因：
                %s

                本轮工具证据：
                %s
                """.formatted(recognitionJson, assessmentJson, evidenceJson);

        try {
            SpringAiRoutingResult<ResponsePlanStructuredOutput> routing =
                    router.callWithFallback(
                            conversationId.trim(),
                            List.of(new UserMessage(prompt)),
                            ResponsePlanStructuredOutput.class);
            log.info("Spring AI 处置规划完成: provider={}, model={}, fallbackUsed={}, actions={}",
                    routing.provider(), routing.model(), routing.fallbackUsed(),
                    routing.body().actions().size());
            return Optional.of(routing.body());
        } catch (AiProvidersUnavailableException exception) {
            log.warn("Spring AI 处置规划主备模型均不可用，退回 Java 规则方案: primary={}, backup={}",
                    exception.getPrimaryProvider(), exception.getBackupProvider());
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("Spring AI 处置规划结果不可用，退回 Java 规则方案: reason={}",
                    exception.toString());
            return Optional.empty();
        }
    }
}
