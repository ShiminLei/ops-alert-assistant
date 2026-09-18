package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.ai.ToolPlanningAiService;
import com.enterprise.opsassistant.ai.ToolPlanningStructuredOutput;
import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.ToolPlan;
import com.enterprise.opsassistant.tool.OperationsToolCatalog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 使用 Spring AI 制定运维工具调用计划，并由 Java 执行白名单校验的 Agent。
 *
 * <p>Java 规则先生成可独立工作的兜底计划；模型随后可以根据告警语义提出更有针对性的工具。
 * 模型建议只有在 OperationsToolCatalog 中真实注册后才会进入最终计划，而且三个基础调查工具
 * 始终保留。这样既使用模型的动态规划能力，也不允许它调用不存在或具有写操作风险的工具。</p>
 */
@Component
public class ToolPlanningAgent {

    /** 所有调查都要先获取的三项基础事实：服务状态、关键日志和核心指标。 */
    private static final List<String> BASE_TOOLS = List.of(
            "service-status",
            "error-log",
            "resource-usage"
    );

    /**
     * 不同告警类型需要补充的专业工具。
     * Map 是只读规则表，新增告警类型时可以清楚看到其证据策略。
     */
    private static final Map<AlertType, List<String>> SPECIALIZED_TOOLS = Map.of(
            AlertType.API_TIMEOUT, List.of("dependency-status", "database-connection", "deployment"),
            AlertType.ERROR_RATE_SPIKE, List.of("deployment", "dependency-status", "database-connection"),
            AlertType.HIGH_CPU, List.of("deployment", "dependency-status"),
            AlertType.HIGH_MEMORY, List.of("deployment", "dependency-status"),
            AlertType.DATABASE_CONNECTION, List.of("database-connection", "deployment"),
            AlertType.DEPENDENCY_FAILURE, List.of("dependency-status", "deployment"),
            AlertType.POST_DEPLOYMENT_FAILURE, List.of("deployment", "database-connection", "dependency-status"),
            AlertType.UNKNOWN, List.of()
    );

    private final ToolPlanningAiService toolPlanningAiService;
    private final OperationsToolCatalog toolCatalog;

    /** Spring 运行时注入工具规划 AI Service 和真实工具目录。 */
    @Autowired
    public ToolPlanningAgent(ToolPlanningAiService toolPlanningAiService,
                             OperationsToolCatalog toolCatalog) {
        this.toolPlanningAiService = toolPlanningAiService;
        this.toolCatalog = toolCatalog;
    }

    /** 离线领域测试使用纯规则模式；Spring 不会选择这个构造器。 */
    public ToolPlanningAgent() {
        this.toolPlanningAiService = null;
        this.toolCatalog = null;
    }

    /**
     * 从告警识别结果生成有序工具计划。
     *
     * @param recognition 已完成归一化的告警识别结果
     * @return 至少包含三个不同工具的计划
     */
    public ToolPlan plan(AlertRecognition recognition) {
        return plan(recognition, null);
    }

    /**
     * 在当前会话中生成工具计划。模型不可用或建议全部无效时返回完整的 Java 规则计划。
     */
    public ToolPlan plan(AlertRecognition recognition, String conversationId) {
        if (recognition == null) {
            throw new IllegalArgumentException("recognition must not be null");
        }

        ToolPlan rulePlan = planWithRules(recognition);
        if (toolPlanningAiService == null
                || toolCatalog == null
                || conversationId == null
                || conversationId.isBlank()) {
            return rulePlan;
        }

        Optional<ToolPlanningStructuredOutput> candidate =
                toolPlanningAiService.plan(conversationId, recognition);
        return candidate
                .map(value -> mergeAiPlan(recognition, rulePlan, value))
                .orElse(rulePlan);
    }

    /** 保留原来的告警类型规则表，作为模型失败时可预测、可审计的完整降级方案。 */
    private ToolPlan planWithRules(AlertRecognition recognition) {

        List<String> toolNames = java.util.stream.Stream.concat(
                        BASE_TOOLS.stream(),
                        SPECIALIZED_TOOLS.getOrDefault(recognition.alertType(), List.of()).stream())
                .distinct()
                .toList();

        String rationale = "先查询服务状态、日志和核心指标，再根据 "
                + recognition.alertType() + " 补充专项证据";
        return new ToolPlan(recognition.serviceName(), recognition.alertType(), toolNames, rationale);
    }

    /**
     * 校验 AI 工具建议并合成最终计划。
     *
     * <p>基础工具先加入，保证任何调查至少覆盖健康状态、日志和核心指标；AI 建议按原顺序追加，
     * 未注册名称和重复名称直接丢弃。如果模型没有提出任何有效工具，则整个候选作废并使用规则
     * 计划，避免一个看似成功但内容无效的模型响应削弱调查范围。</p>
     */
    private ToolPlan mergeAiPlan(AlertRecognition recognition,
                                 ToolPlan rulePlan,
                                 ToolPlanningStructuredOutput candidate) {
        List<String> validSuggestions = candidate.toolNames().stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .filter(toolCatalog::contains)
                .distinct()
                .toList();
        if (validSuggestions.isEmpty()) {
            return rulePlan;
        }

        LinkedHashSet<String> safeTools = new LinkedHashSet<>(BASE_TOOLS);
        safeTools.addAll(validSuggestions);
        String rationale = candidate.rationale()
                + "；Java 已执行工具白名单校验并强制保留三项基础调查";
        return new ToolPlan(
                recognition.serviceName(),
                recognition.alertType(),
                List.copyOf(safeTools),
                rationale
        );
    }
}
