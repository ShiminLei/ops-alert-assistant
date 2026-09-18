package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.exception.AiProvidersUnavailableException;
import com.enterprise.opsassistant.tool.OperationsToolCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** 使用 Spring AI 根据已识别告警动态提出运维工具调用计划。 */
@Service
public class ToolPlanningAiService {

    private static final Logger log = LoggerFactory.getLogger(ToolPlanningAiService.class);

    private final SpringAiModelRouter router;
    private final OperationsToolCatalog toolCatalog;
    private final ObjectMapper objectMapper;

    public ToolPlanningAiService(SpringAiModelRouter router,
                                 OperationsToolCatalog toolCatalog,
                                 ObjectMapper objectMapper) {
        this.router = router;
        this.toolCatalog = toolCatalog;
        this.objectMapper = objectMapper;
    }

    /**
     * 让主备模型从动态白名单中选择工具；模型或结构化转换失败时返回空结果，由规则计划接管。
     */
    public Optional<ToolPlanningStructuredOutput> plan(
            String conversationId,
            AlertRecognition recognition) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (recognition == null) {
            throw new IllegalArgumentException("recognition must not be null");
        }

        String recognitionJson;
        try {
            recognitionJson = objectMapper.writeValueAsString(recognition);
        } catch (JsonProcessingException exception) {
            log.warn("工具规划上下文序列化失败，退回 Java 规则计划: reason={}", exception.toString());
            return Optional.empty();
        }

        String prompt = """
                [TASK:TOOL_PLANNING]
                你是企业运维调查规划 Agent。请根据结构化告警，从已注册工具中选择调查工具。

                约束：
                1. toolNames 只能使用下方目录中的精确工具名，不得创造工具。
                2. 选择顺序应体现先确认现状、再验证专项假设的调查逻辑。
                3. 不执行任何变更操作，只能选择只读查询工具。
                4. rationale 用简短中文解释工具与当前告警的关系。

                已注册工具目录：
                %s

                结构化告警：
                %s
                """.formatted(toolCatalog.promptContext(), recognitionJson);

        try {
            SpringAiRoutingResult<ToolPlanningStructuredOutput> routing =
                    router.callWithFallback(
                            conversationId.trim(),
                            List.of(new UserMessage(prompt)),
                            ToolPlanningStructuredOutput.class);
            log.info("Spring AI 工具规划完成: provider={}, model={}, fallbackUsed={}, tools={}",
                    routing.provider(), routing.model(), routing.fallbackUsed(),
                    routing.body().toolNames());
            return Optional.of(routing.body());
        } catch (AiProvidersUnavailableException exception) {
            log.warn("Spring AI 工具规划主备模型均不可用，退回 Java 规则计划: primary={}, backup={}",
                    exception.getPrimaryProvider(), exception.getBackupProvider());
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("Spring AI 工具规划结果不可用，退回 Java 规则计划: reason={}",
                    exception.toString());
            return Optional.empty();
        }
    }
}
