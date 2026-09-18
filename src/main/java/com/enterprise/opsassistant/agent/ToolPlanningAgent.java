package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.ToolPlan;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 根据告警类型制定运维工具调用计划的规则型 Agent。
 *
 * <p>当前版本不调用大模型，而是作为系统的确定性安全底线。未来模型可以提出更多工具，
 * 但至少要保留这里规定的基础证据组合，防止模型只凭一条日志就直接下结论。</p>
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

    /**
     * 从告警识别结果生成有序工具计划。
     *
     * @param recognition 已完成归一化的告警识别结果
     * @return 至少包含三个不同工具的计划
     */
    public ToolPlan plan(AlertRecognition recognition) {
        if (recognition == null) {
            throw new IllegalArgumentException("recognition must not be null");
        }

        List<String> toolNames = java.util.stream.Stream.concat(
                        BASE_TOOLS.stream(),
                        SPECIALIZED_TOOLS.getOrDefault(recognition.alertType(), List.of()).stream())
                .distinct()
                .toList();

        String rationale = "先查询服务状态、日志和核心指标，再根据 "
                + recognition.alertType() + " 补充专项证据";
        return new ToolPlan(recognition.serviceName(), recognition.alertType(), toolNames, rationale);
    }
}
