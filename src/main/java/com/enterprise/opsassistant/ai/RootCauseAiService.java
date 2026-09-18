package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.EvidenceCollectionResult;
import com.enterprise.opsassistant.exception.AiProvidersUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** 使用 Spring AI 交叉分析告警事实和真实工具证据，提出结构化根因候选。 */
@Service
public class RootCauseAiService {

    private static final Logger log = LoggerFactory.getLogger(RootCauseAiService.class);

    private final SpringAiModelRouter router;
    private final ObjectMapper objectMapper;

    public RootCauseAiService(SpringAiModelRouter router, ObjectMapper objectMapper) {
        this.router = router;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用主备模型综合证据；模型、路由或结构化转换失败时返回空结果，由 Java 规则完整接管。
     *
     * <p>提示词把 evidenceId 明确暴露给模型，是为了让结论可追溯，而不是授权模型自由创造编号。
     * 上层 RootCauseAgent 仍会用本轮实际证据集合重新校验所有引用。</p>
     */
    public Optional<RootCauseStructuredOutput> analyze(
            String conversationId,
            AlertRecognition recognition,
            EvidenceCollectionResult collection) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (recognition == null) {
            throw new IllegalArgumentException("recognition must not be null");
        }
        if (collection == null) {
            throw new IllegalArgumentException("collection must not be null");
        }

        String recognitionJson;
        String evidenceJson;
        try {
            recognitionJson = objectMapper.writeValueAsString(recognition);
            evidenceJson = objectMapper.writeValueAsString(collection.evidence());
        } catch (JsonProcessingException exception) {
            log.warn("根因分析上下文序列化失败，退回 Java 规则分析: reason={}", exception.toString());
            return Optional.empty();
        }

        String prompt = """
                [TASK:ROOT_CAUSE_ANALYSIS]
                你是企业运维根因分析 Agent。请只根据下面的结构化告警和工具证据提出根因候选。

                约束：
                1. 每个候选必须引用一项或多项输入中真实存在的 evidenceId。
                2. FAILED 状态的证据只能说明数据缺失，不能用于证明某个根因。
                3. 不得创造指标、日志、发布记录、服务或证据编号。
                4. confidence 范围为 0 到 1，表示当前证据支持程度，不是统计概率。
                5. 证据不足时应明确表达不确定性，不得强行给出确定根因。
                6. 只分析原因，不执行回滚、扩容、限流或配置变更。

                结构化告警：
                %s

                本轮工具证据：
                %s
                """.formatted(recognitionJson, evidenceJson);

        try {
            SpringAiRoutingResult<RootCauseStructuredOutput> routing =
                    router.callWithFallback(
                            conversationId.trim(),
                            List.of(new UserMessage(prompt)),
                            RootCauseStructuredOutput.class);
            log.info("Spring AI 根因综合完成: provider={}, model={}, fallbackUsed={}, candidates={}",
                    routing.provider(), routing.model(), routing.fallbackUsed(),
                    routing.body().candidates().size());
            return Optional.of(routing.body());
        } catch (AiProvidersUnavailableException exception) {
            log.warn("Spring AI 根因综合主备模型均不可用，退回 Java 规则分析: primary={}, backup={}",
                    exception.getPrimaryProvider(), exception.getBackupProvider());
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("Spring AI 根因综合结果不可用，退回 Java 规则分析: reason={}",
                    exception.toString());
            return Optional.empty();
        }
    }
}
