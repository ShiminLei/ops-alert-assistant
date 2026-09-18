package com.enterprise.opsassistant.ai;

import com.enterprise.opsassistant.domain.ActionUrgency;

import java.util.List;
import java.util.Objects;

/**
 * Spring AI 在处置规划阶段返回的结构化建议。
 *
 * <p>模型建议还不是最终处置方案。ResponsePlanAgent 会校验动作类型、责任角色、人工审批标记、
 * 证据编号和危险关键词，并把通过校验的建议与 Java 强制安全动作合并。</p>
 */
public record ResponsePlanStructuredOutput(
        List<Action> actions,
        List<String> followUpMetrics,
        String rationale) {

    /** 归一化模型可能省略的数组；安全校验由 Agent 集中完成。 */
    public ResponsePlanStructuredOutput {
        actions = List.copyOf(Objects.requireNonNullElse(actions, List.of()));
        followUpMetrics = List.copyOf(Objects.requireNonNullElse(followUpMetrics, List.of()));
        rationale = rationale == null ? "" : rationale.trim();
    }

    /**
     * 单条模型动作候选。
     *
     * @param type 受控动作类型，避免模型用自由文本伪装成任意系统能力
     * @param urgency 建议紧急程度
     * @param action 面向运维人员的动作说明，不允许包含可直接执行的危险命令
     * @param ownerRole 固定责任角色
     * @param requiresHumanApproval 是否明确要求人工审批生产变更
     * @param evidenceIds 支持该建议的本轮工具证据编号
     */
    public record Action(
            ActionType type,
            ActionUrgency urgency,
            String action,
            OwnerRole ownerRole,
            boolean requiresHumanApproval,
            List<String> evidenceIds) {

        public Action {
            evidenceIds = List.copyOf(Objects.requireNonNullElse(evidenceIds, List.of()));
        }
    }

    /** 模型可提出的动作种类；这里没有“执行命令”或“删除数据”等能力。 */
    public enum ActionType {
        INVESTIGATE,
        MITIGATE,
        ROLLBACK,
        SCALE,
        CONFIG_CHANGE,
        ESCALATE,
        OBSERVE,
        REMEDIATE
    }

    /** 模型只能把动作分派给这些运维角色，不能虚构个人或外部主体。 */
    public enum OwnerRole {
        INCIDENT_COMMANDER,
        APPLICATION_ON_CALL,
        PLATFORM_OPERATIONS,
        DBA,
        APPLICATION_ENGINEER,
        APPLICATION_OWNER,
        DEPENDENCY_OWNER,
        SECURITY_TEAM
    }
}
