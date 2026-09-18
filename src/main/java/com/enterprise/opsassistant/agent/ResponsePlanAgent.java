package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.domain.ActionUrgency;
import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.EvidenceCollectionResult;
import com.enterprise.opsassistant.domain.RecommendedAction;
import com.enterprise.opsassistant.domain.ResponsePlan;
import com.enterprise.opsassistant.domain.RiskLevel;
import com.enterprise.opsassistant.domain.RootCauseAssessment;
import com.enterprise.opsassistant.domain.ToolEvidence;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 根据根因评估生成有顺序、紧急程度和责任人的处置方案。
 *
 * <p>本 Agent 只提出建议，不会直接调用发布平台回滚，也不会修改数据库或生产配置。
 * 这是运维 AI 的重要权限边界：分析和建议可以自动化，高风险变更仍需有权限的人员确认执行。</p>
 *
 * <p>Java 规则确保高风险告警一定包含升级动作、建议回滚时一定给出目标版本、数据库饱和时
 * 一定同时包含止损与后续修复。未来模型可以把动作描述得更贴近现场，但不能删除这些安全动作。</p>
 */
@Component
public class ResponsePlanAgent {

    /**
     * 生成结构化处置计划。
     *
     * @param recognition 用户原始告警的结构化识别结果
     * @param assessment 根因、最终风险、回滚和升级判断
     * @param collection 实际工具证据，用于读取版本、连接池和依赖等处置细节
     * @return 经过顺序与观察指标校验的处置方案
     */
    public ResponsePlan plan(AlertRecognition recognition,
                             RootCauseAssessment assessment,
                             EvidenceCollectionResult collection) {
        if (recognition == null || assessment == null || collection == null) {
            throw new IllegalArgumentException("recognition, assessment and collection must not be null");
        }
        if (!recognition.serviceName().equals(collection.plan().serviceName())) {
            throw new IllegalArgumentException("recognition and evidence must refer to the same service");
        }

        List<ActionDraft> drafts = new ArrayList<>();
        Set<String> followUpMetrics = new LinkedHashSet<>();

        addEscalationAction(drafts, recognition, assessment);
        addRollbackAction(drafts, recognition, assessment, collection);
        addDatabaseActions(drafts, assessment, collection, followUpMetrics);
        addDependencyActions(drafts, assessment, collection, followUpMetrics);
        addResourceActions(drafts, assessment, collection, followUpMetrics);
        addEvidenceGapAction(drafts, collection);
        addCommonActions(drafts, recognition, assessment, followUpMetrics);

        List<RecommendedAction> orderedActions = numberActions(drafts);
        String summary = buildSummary(recognition, assessment, orderedActions);
        return new ResponsePlan(orderedActions, List.copyOf(followUpMetrics), summary);
    }

    /** 高风险且需要人工升级时，第一步先建立响应责任和沟通通道。 */
    private void addEscalationAction(List<ActionDraft> drafts,
                                     AlertRecognition recognition,
                                     RootCauseAssessment assessment) {
        if (!assessment.escalationRequired()) {
            return;
        }
        drafts.add(new ActionDraft(
                ActionUrgency.IMMEDIATE,
                "立即升级 " + recognition.serviceName() + " 事故响应，通知应用值班、平台运维和相关负责人",
                "事故指挥官"
        ));
    }

    /**
     * 根因评估明确建议回滚时，从发布证据中读取当前版本与上一版本，生成可核对的回滚建议。
     */
    private void addRollbackAction(List<ActionDraft> drafts,
                                   AlertRecognition recognition,
                                   RootCauseAssessment assessment,
                                   EvidenceCollectionResult collection) {
        if (!assessment.rollbackRecommended()) {
            return;
        }

        Optional<ToolEvidence> deployment = evidence(collection, "deployment");
        String currentVersion = deployment.map(item -> text(item, "version")).orElse("当前故障版本");
        String previousVersion = deployment.map(item -> text(item, "previousVersion")).orElse("上一稳定版本");
        drafts.add(new ActionDraft(
                ActionUrgency.IMMEDIATE,
                "经人工确认后，将 " + recognition.serviceName() + " 从 "
                        + currentVersion + " 回滚到 " + previousVersion,
                "应用值班"
        ));
    }

    /** 数据库连接池异常需要同时处理当前积压和长期配置问题。 */
    private void addDatabaseActions(List<ActionDraft> drafts,
                                    RootCauseAssessment assessment,
                                    EvidenceCollectionResult collection,
                                    Set<String> followUpMetrics) {
        if (!hasCandidate(assessment, "数据库连接池") && evidence(collection, "database-connection").isEmpty()) {
            return;
        }

        Optional<ToolEvidence> database = evidence(collection, "database-connection");
        double active = database.map(item -> number(item, "activeConnections")).orElse(0.0);
        double maximum = database.map(item -> number(item, "maxConnections")).orElse(0.0);
        double waiting = database.map(item -> number(item, "waitingThreads")).orElse(0.0);
        boolean saturated = maximum > 0 && (active >= maximum || waiting > 0);

        if (saturated) {
            drafts.add(new ActionDraft(
                    ActionUrgency.IMMEDIATE,
                    "检查数据库承载能力后，临时恢复合理的连接池容量并清理积压请求；禁止盲目无限扩容",
                    "应用值班 / DBA"
            ));
        }
        drafts.add(new ActionDraft(
                ActionUrgency.SHORT_TERM,
                "分析连接持有时间和慢 SQL，修复连接未及时释放或查询耗时过长的问题",
                "应用研发 / DBA"
        ));

        followUpMetrics.add("数据库活跃连接数/最大连接数");
        followUpMetrics.add("数据库连接等待线程数");
        followUpMetrics.add("平均查询耗时");
    }

    /** 依赖异常时建议先限流或降级，随后由依赖负责人排查真实故障。 */
    private void addDependencyActions(List<ActionDraft> drafts,
                                      RootCauseAssessment assessment,
                                      EvidenceCollectionResult collection,
                                      Set<String> followUpMetrics) {
        Optional<ToolEvidence> dependency = evidence(collection, "dependency-status");
        boolean abnormal = dependency.map(item -> number(item, "abnormalCount") > 0).orElse(false);
        if (!abnormal && !hasCandidate(assessment, "下游依赖")) {
            return;
        }

        drafts.add(new ActionDraft(
                assessment.finalRisk().atLeast(RiskLevel.HIGH)
                        ? ActionUrgency.IMMEDIATE : ActionUrgency.SHORT_TERM,
                "对异常下游依赖启用超时、限流或降级策略，并联系依赖服务负责人确认恢复时间",
                "应用值班 / 依赖服务负责人"
        ));
        followUpMetrics.add("异常依赖调用延迟");
        followUpMetrics.add("异常依赖成功率");
    }

    /** 资源指标异常时先保护服务，再进行容量和代码层面的修复。 */
    private void addResourceActions(List<ActionDraft> drafts,
                                    RootCauseAssessment assessment,
                                    EvidenceCollectionResult collection,
                                    Set<String> followUpMetrics) {
        Optional<ToolEvidence> resources = evidence(collection, "resource-usage");
        if (resources.isEmpty()) {
            return;
        }

        double cpu = resources.map(item -> number(item, "cpuPercent")).orElse(0.0);
        double memory = resources.map(item -> number(item, "memoryPercent")).orElse(0.0);
        if (cpu >= 80 || memory >= 85) {
            drafts.add(new ActionDraft(
                    assessment.finalRisk().atLeast(RiskLevel.HIGH)
                            ? ActionUrgency.IMMEDIATE : ActionUrgency.SHORT_TERM,
                    "核查热点实例和线程/内存使用，必要时在容量允许范围内临时扩容并限制异常流量",
                    "平台运维 / 应用值班"
            ));
        }

        followUpMetrics.add("服务错误率");
        followUpMetrics.add("P99 响应延迟");
        followUpMetrics.add("CPU 使用率");
        followUpMetrics.add("内存使用率");
    }

    /** 证据不足时不能假装结论确定，必须明确补采数据并保留人工判断。 */
    private void addEvidenceGapAction(List<ActionDraft> drafts, EvidenceCollectionResult collection) {
        if (collection.failureCount() == 0 && collection.hasEnoughEvidence()) {
            return;
        }
        drafts.add(new ActionDraft(
                ActionUrgency.SHORT_TERM,
                "补采失败或缺失的运维证据，在证据完整前避免执行不可逆变更",
                "平台运维"
        ));
    }

    /** 所有方案都应包含复盘修复和恢复验证，健康提醒则仅保留低风险核实与观察。 */
    private void addCommonActions(List<ActionDraft> drafts,
                                  AlertRecognition recognition,
                                  RootCauseAssessment assessment,
                                  Set<String> followUpMetrics) {
        if (drafts.isEmpty()) {
            drafts.add(new ActionDraft(
                    ActionUrgency.OBSERVATION,
                    "核实告警来源并继续观察，当前证据暂不支持执行生产变更",
                    "应用值班"
            ));
        } else if (assessment.finalRisk().atLeast(RiskLevel.MEDIUM)) {
            drafts.add(new ActionDraft(
                    ActionUrgency.SHORT_TERM,
                    "故障恢复后完成复盘，补充配置变更校验、容量基线和对应自动化测试",
                    "应用负责人"
            ));
        }

        drafts.add(new ActionDraft(
                ActionUrgency.OBSERVATION,
                "持续观察两个稳定窗口，确认核心指标恢复且没有再次恶化后再关闭事故",
                "应用值班"
        ));
        followUpMetrics.add("服务可用率");
        followUpMetrics.add("健康实例数");
        if (recognition.userImpact()) {
            followUpMetrics.add("用户请求成功率");
        }
    }

    /** 为草稿统一生成从 1 开始的连续 order，避免各规则自行管理编号。 */
    private List<RecommendedAction> numberActions(List<ActionDraft> drafts) {
        List<RecommendedAction> actions = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            ActionDraft draft = drafts.get(index);
            actions.add(new RecommendedAction(
                    index + 1,
                    draft.urgency(),
                    draft.action(),
                    draft.owner()
            ));
        }
        return List.copyOf(actions);
    }

    /** 生成适合最终报告标题区域展示的策略概述。 */
    private String buildSummary(AlertRecognition recognition,
                                RootCauseAssessment assessment,
                                List<RecommendedAction> actions) {
        long immediateCount = actions.stream()
                .filter(action -> action.urgency() == ActionUrgency.IMMEDIATE)
                .count();
        return "针对 " + recognition.serviceName() + " 的 " + assessment.finalRisk()
                + " 风险生成 " + actions.size() + " 项处置动作，其中立即动作 "
                + immediateCount + " 项；所有生产变更均需人工确认";
    }

    /** 查找成功或部分成功的指定工具证据，失败证据不能作为操作参数来源。 */
    private Optional<ToolEvidence> evidence(EvidenceCollectionResult collection, String toolName) {
        return collection.evidence().stream()
                .filter(item -> item.toolName().equals(toolName))
                .filter(item -> item.status() != com.enterprise.opsassistant.domain.EvidenceStatus.FAILED)
                .findFirst();
    }

    /** 判断根因候选描述是否包含指定业务关键词。 */
    private boolean hasCandidate(RootCauseAssessment assessment, String keyword) {
        String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
        return assessment.candidates().stream()
                .anyMatch(candidate -> candidate.description().toLowerCase(Locale.ROOT).contains(lowerKeyword));
    }

    /** 安全读取工具证据中的文本值。 */
    private String text(ToolEvidence evidence, String key) {
        Object value = evidence.data().get(key);
        return value == null || value.toString().isBlank() ? "未知" : value.toString();
    }

    /** 安全读取工具证据中的数值；缺失或格式错误时返回 0。 */
    private double number(ToolEvidence evidence, String key) {
        Object value = evidence.data().get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** 内部动作草稿不带 order；所有规则结束后再集中编号。 */
    private record ActionDraft(ActionUrgency urgency, String action, String owner) {
    }
}
