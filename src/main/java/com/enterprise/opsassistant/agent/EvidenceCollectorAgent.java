package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.domain.EvidenceCollectionResult;
import com.enterprise.opsassistant.domain.EvidenceStatus;
import com.enterprise.opsassistant.domain.ToolEvidence;
import com.enterprise.opsassistant.domain.ToolPlan;
import com.enterprise.opsassistant.tool.OperationsTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 按计划调用运维工具并汇总证据的 Agent。
 *
 * <p>该 Agent 负责“执行”，不负责判断根因。职责分离后，工具调用异常只会形成 FAILED 证据，
 * 其余工具仍能继续执行。这样既满足基础调用链日志要求，也使后续根因分析能够明确看到
 * 哪部分数据缺失，而不是得到一份悄悄丢失证据的报告。</p>
 */
@Component
public class EvidenceCollectorAgent {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCollectorAgent.class);

    /** 按工具名索引 Spring 注入的全部工具，并保持注册顺序方便调试。 */
    private final Map<String, OperationsTool> toolsByName;

    /**
     * Spring 会自动注入所有 OperationsTool 实现。
     * 构造时检查重名，避免两个实现使用同一工具名导致其中一个被静默覆盖。
     */
    public EvidenceCollectorAgent(List<OperationsTool> tools) {
        Map<String, OperationsTool> indexedTools = new LinkedHashMap<>();
        for (OperationsTool tool : tools) {
            OperationsTool previous = indexedTools.putIfAbsent(tool.name(), tool);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate operations tool name: " + tool.name());
            }
        }
        this.toolsByName = Map.copyOf(indexedTools);
    }

    /**
     * 依照计划顺序逐个调用真实工具实现。
     *
     * @param plan 已通过领域约束、至少包含三个工具的调用计划
     * @return 数量与计划完全对应的证据汇总
     */
    public EvidenceCollectionResult collect(ToolPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }

        log.info("开始收集运维证据: service={}, alertType={}, tools={}",
                plan.serviceName(), plan.alertType(), plan.toolNames());

        List<ToolEvidence> evidence = new ArrayList<>();
        for (String toolName : plan.toolNames()) {
            evidence.add(invokeSafely(toolName, plan.serviceName()));
        }

        long successCount = countByStatus(evidence, EvidenceStatus.SUCCESS);
        long partialCount = countByStatus(evidence, EvidenceStatus.PARTIAL);
        long failureCount = countByStatus(evidence, EvidenceStatus.FAILED);

        log.info("运维证据收集完成: service={}, success={}, partial={}, failed={}",
                plan.serviceName(), successCount, partialCount, failureCount);

        return new EvidenceCollectionResult(
                plan,
                evidence,
                successCount,
                partialCount,
                failureCount
        );
    }

    /**
     * 安全调用单个工具。不存在的工具和运行期异常都会转换为 FAILED 证据，
     * 从而保证一个工具故障不会阻断其余证据收集。
     */
    private ToolEvidence invokeSafely(String toolName, String serviceName) {
        OperationsTool tool = toolsByName.get(toolName);
        if (tool == null) {
            log.error("工具计划引用了未注册工具: tool={}, service={}", toolName, serviceName);
            return failureEvidence(toolName, serviceName, "tool is not registered");
        }

        try {
            ToolEvidence result = tool.execute(serviceName);
            log.info("工具调用完成: tool={}, service={}, status={}, durationMs={}",
                    toolName, serviceName, result.status(), result.durationMs());
            return result;
        } catch (RuntimeException exception) {
            log.error("工具调用异常: tool={}, service={}", toolName, serviceName, exception);
            return failureEvidence(toolName, serviceName, exception.getMessage());
        }
    }

    /** 为编排错误或工具异常创建统一的失败证据。 */
    private ToolEvidence failureEvidence(String toolName, String serviceName, String errorMessage) {
        return new ToolEvidence(
                toolName + "-" + UUID.randomUUID(),
                toolName,
                serviceName,
                EvidenceStatus.FAILED,
                "工具调用失败，未获得可用证据",
                Map.of(),
                errorMessage == null || errorMessage.isBlank() ? "unknown tool error" : errorMessage,
                0,
                Instant.now()
        );
    }

    /** 按状态统计证据数量，集中实现避免三段重复流处理。 */
    private long countByStatus(List<ToolEvidence> evidence, EvidenceStatus status) {
        return evidence.stream().filter(item -> item.status() == status).count();
    }
}
