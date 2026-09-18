package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.catalog.ServiceCatalog;
import com.enterprise.opsassistant.exception.AiProvidersUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 使用 Spring AI 把非结构化告警转换成强类型候选结果。
 *
 * <p>该服务只负责模型交互：构造告警理解提示词、通过主备路由调用 ChatClient，并要求 Spring AI
 * 将模型响应转换成 {@link AlertUnderstandingStructuredOutput}。它不直接决定哪些模型字段可信，
 * 因为服务名白名单、指标真实性和风险下限属于 Java 领域边界，应由 AlertParserAgent 统一校验。</p>
 */
@Service
public class AlertUnderstandingAiService {

    private static final Logger log = LoggerFactory.getLogger(AlertUnderstandingAiService.class);

    private final SpringAiModelRouter router;
    private final ServiceCatalog serviceCatalog;

    public AlertUnderstandingAiService(SpringAiModelRouter router,
                                       ServiceCatalog serviceCatalog) {
        this.router = router;
        this.serviceCatalog = serviceCatalog;
    }

    /**
     * 调用主模型理解告警；主模型失败时由路由器切换备用模型，主备均失败则返回空结果。
     *
     * <p>返回 Optional.empty() 是有意的降级协议：告警处理不能因为模型暂时不可用而完全停止，
     * 上层会继续使用 Java 规则基线。conversationId 使用分析会话编号，使同一会话的模型调用
     * 可以被 Spring AI Advisor 关联，同时不会把技术性的 Provider 选择泄漏到 Agent 中。</p>
     */
    public Optional<AlertUnderstandingStructuredOutput> understand(
            String conversationId,
            String rawAlert) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        if (rawAlert == null || rawAlert.isBlank()) {
            throw new IllegalArgumentException("rawAlert must not be blank");
        }

        String prompt = """
                [TASK:ALERT_UNDERSTANDING]
                你是企业运维告警理解 Agent。请只理解下面原始告警明确表达的内容，输出结构化结果。

                约束：
                1. serviceName 只能从“已注册服务目录”的标准名中选择；无法判断时返回 unknown-service。
                2. alertType 只能使用目标 JSON Schema 中的枚举值。
                3. abnormalMetrics 只能包含原文明确出现的数值，不得编造监控数据。
                4. initialRisk 仅表示收集工具证据前的初始风险。
                5. summary 使用简短中文，不提出尚未由工具证据证明的根因。

                已注册服务目录：
                %s

                原始告警：
                %s
                """.formatted(serviceCatalog.promptContext(), rawAlert.trim());

        try {
            SpringAiRoutingResult<AlertUnderstandingStructuredOutput> routing =
                    router.callWithFallback(
                            conversationId.trim(),
                            List.of(new UserMessage(prompt)),
                            AlertUnderstandingStructuredOutput.class);
            log.info("Spring AI 告警理解完成: provider={}, model={}, fallbackUsed={}",
                    routing.provider(), routing.model(), routing.fallbackUsed());
            return Optional.of(routing.body());
        } catch (AiProvidersUnavailableException exception) {
            log.warn("Spring AI 告警理解主备模型均不可用，退回 Java 规则解析: primary={}, backup={}",
                    exception.getPrimaryProvider(), exception.getBackupProvider());
            return Optional.empty();
        } catch (RuntimeException exception) {
            // 结构化转换、字段校验等异常同样不能阻断告警处理，统一退回可预测的规则结果。
            log.warn("Spring AI 告警理解结果不可用，退回 Java 规则解析: reason={}",
                    exception.toString());
            return Optional.empty();
        }
    }
}
