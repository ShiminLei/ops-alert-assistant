package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.ai.ResponsePlanAiService;
import com.enterprise.opsassistant.ai.ResponsePlanStructuredOutput;
import com.enterprise.opsassistant.domain.ActionUrgency;
import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.EvidenceCollectionResult;
import com.enterprise.opsassistant.domain.RecommendedAction;
import com.enterprise.opsassistant.domain.ResponsePlan;
import com.enterprise.opsassistant.domain.RiskLevel;
import com.enterprise.opsassistant.domain.RootCauseAssessment;
import com.enterprise.opsassistant.domain.ToolEvidence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.Map;

/**
 * 根据根因评估生成有顺序、紧急程度和责任人的处置方案。
 *
 * <p>本 Agent 只提出建议，不会直接调用发布平台回滚，也不会修改数据库或生产配置。
 * 这是运维 AI 的重要权限边界：分析和建议可以自动化，高风险变更仍需有权限的人员确认执行。</p>
 *
 * <p>Java 规则确保高风险告警一定包含升级动作、建议回滚时一定给出目标版本、数据库饱和时
 * 一定同时包含止损与后续修复。模型可以把动作描述得更贴近现场，但不能删除这些安全动作。</p>
 */
@Component
public class ResponsePlanAgent {

    /** 模型只能补充这些可观察指标；最终规则指标始终保留。 */
    private static final Set<String> SAFE_FOLLOW_UP_METRICS = Set.of(
            "服务可用率", "健康实例数", "用户请求成功率", "服务错误率", "P99 响应延迟",
            "CPU 使用率", "内存使用率", "数据库活跃连接数/最大连接数", "数据库连接等待线程数",
            "平均查询耗时", "异常依赖调用延迟", "异常依赖成功率"
    );

    /** 出现在模型自由文本中的高危险操作会让整条候选失效。 */
    private static final List<String> FORBIDDEN_ACTION_FRAGMENTS = List.of(
            "rm -rf", "drop table", "truncate table", "delete from", "删除生产数据",
            "关闭数据库", "绕过审批", "跳过审批", "禁用审计", "清空数据库"
    );

    /** 把受控枚举责任角色转换为最终报告中的中文角色名称。 */
    private static final Map<ResponsePlanStructuredOutput.OwnerRole, String> OWNER_NAMES = Map.of(
            ResponsePlanStructuredOutput.OwnerRole.INCIDENT_COMMANDER, "事故指挥官",
            ResponsePlanStructuredOutput.OwnerRole.APPLICATION_ON_CALL, "应用值班",
            ResponsePlanStructuredOutput.OwnerRole.PLATFORM_OPERATIONS, "平台运维",
            ResponsePlanStructuredOutput.OwnerRole.DBA, "DBA",
            ResponsePlanStructuredOutput.OwnerRole.APPLICATION_ENGINEER, "应用研发",
            ResponsePlanStructuredOutput.OwnerRole.APPLICATION_OWNER, "应用负责人",
            ResponsePlanStructuredOutput.OwnerRole.DEPENDENCY_OWNER, "依赖服务负责人",
            ResponsePlanStructuredOutput.OwnerRole.SECURITY_TEAM, "安全团队"
    );

    private final ResponsePlanAiService responsePlanAiService;

    /** Spring 运行时注入使用 ChatClient 的处置规划服务。 */
    @Autowired
    public ResponsePlanAgent(ResponsePlanAiService responsePlanAiService) {
        this.responsePlanAiService = responsePlanAiService;
    }

    /** 离线领域测试使用纯规则模式，不产生隐藏模型调用。 */
    public ResponsePlanAgent() {
        this.responsePlanAiService = null;
    }

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
        return plan(recognition, assessment, collection, null);
    }

    /**
     * 先生成不可缺失的 Java 规则方案，再合并通过安全校验的 AI 候选动作。
     */
    public ResponsePlan plan(AlertRecognition recognition,
                             RootCauseAssessment assessment,
                             EvidenceCollectionResult collection,
                             String conversationId) {
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

        ResponsePlan rulePlan = buildPlan(recognition, assessment, drafts, followUpMetrics);
        if (responsePlanAiService == null
                || conversationId == null
                || conversationId.isBlank()) {
            return rulePlan;
        }

        return responsePlanAiService.plan(conversationId, recognition, assessment, collection)
                .map(output -> mergeAiPlan(
                        recognition, assessment, collection, rulePlan, output))
                .orElse(rulePlan);
    }

    /** 将规则草稿转换为顺序连续、指标非空的完整方案。 */
    private ResponsePlan buildPlan(AlertRecognition recognition,
                                   RootCauseAssessment assessment,
                                   List<ActionDraft> drafts,
                                   Set<String> followUpMetrics) {
        List<RecommendedAction> orderedActions = numberActions(drafts);
        String summary = buildSummary(recognition, assessment, orderedActions);
        return new ResponsePlan(orderedActions, List.copyOf(followUpMetrics), summary);
    }

    /**
     * 合并 AI 处置建议；Java 规则动作永远保留，模型只能安全地增加建议和观察指标。
     */
    private ResponsePlan mergeAiPlan(AlertRecognition recognition,
                                     RootCauseAssessment assessment,
                                     EvidenceCollectionResult collection,
                                     ResponsePlan rulePlan,
                                     ResponsePlanStructuredOutput output) {
        Set<String> usableEvidenceIds = collection.evidence().stream()
                .filter(item -> item.status() != com.enterprise.opsassistant.domain.EvidenceStatus.FAILED)
                .map(ToolEvidence::evidenceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<ActionDraft> aiDrafts = output.actions().stream()
                .map(action -> validateAiAction(action, assessment, usableEvidenceIds))
                .flatMap(Optional::stream)
                .toList();
        if (aiDrafts.isEmpty()) {
            return rulePlan;
        }

        List<ActionDraft> combined = new ArrayList<>();
        rulePlan.actions().forEach(action -> combined.add(
                new ActionDraft(action.urgency(), action.action(), action.owner())));
        Set<String> existingDescriptions = combined.stream()
                .map(action -> action.action().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        aiDrafts.stream()
                .filter(action -> existingDescriptions.add(action.action().toLowerCase(Locale.ROOT)))
                .forEach(combined::add);
        // 紧急动作始终排在短期和观察动作之前；流排序稳定，因此规则动作内部次序不会改变。
        combined.sort(java.util.Comparator.comparingInt(action -> urgencyRank(action.urgency())));

        LinkedHashSet<String> metrics = new LinkedHashSet<>(rulePlan.followUpMetrics());
        output.followUpMetrics().stream()
                .filter(metric -> metric != null && !metric.isBlank())
                .map(String::trim)
                .filter(SAFE_FOLLOW_UP_METRICS::contains)
                .forEach(metrics::add);

        List<RecommendedAction> actions = numberActions(combined);
        String summary = buildSummary(recognition, assessment, actions)
                + "；已接受 " + aiDrafts.size() + " 项经过证据和权限校验的 AI 补充建议";
        return new ResponsePlan(actions, List.copyOf(metrics), summary);
    }

    /** 校验模型动作的权限、证据、风险级别和文本安全边界。 */
    private Optional<ActionDraft> validateAiAction(
            ResponsePlanStructuredOutput.Action candidate,
            RootCauseAssessment assessment,
            Set<String> usableEvidenceIds) {
        if (candidate == null
                || candidate.type() == null
                || candidate.urgency() == null
                || candidate.ownerRole() == null
                || candidate.action() == null
                || candidate.action().isBlank()) {
            return Optional.empty();
        }

        String actionText = candidate.action().trim();
        String lowerAction = actionText.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_ACTION_FRAGMENTS.stream().anyMatch(lowerAction::contains)) {
            return Optional.empty();
        }

        // 不能只相信模型自行填写的 type。文本出现生产变更语义时同样执行人工审批约束，
        // 防止模型把“重启”错误标成 INVESTIGATE 从而绕过类型校验。
        boolean textSuggestsProductionChange = java.util.stream.Stream.of(
                        "回滚", "扩容", "修改配置", "变更配置", "重启", "限流", "降级", "摘除", "切换流量")
                .anyMatch(lowerAction::contains);
        boolean productionChange = candidate.type() == ResponsePlanStructuredOutput.ActionType.ROLLBACK
                || candidate.type() == ResponsePlanStructuredOutput.ActionType.SCALE
                || candidate.type() == ResponsePlanStructuredOutput.ActionType.CONFIG_CHANGE
                || textSuggestsProductionChange;
        if (productionChange && !candidate.requiresHumanApproval()) {
            return Optional.empty();
        }
        if (candidate.type() == ResponsePlanStructuredOutput.ActionType.ROLLBACK
                && !assessment.rollbackRecommended()) {
            return Optional.empty();
        }
        if (candidate.type() == ResponsePlanStructuredOutput.ActionType.ESCALATE
                && !assessment.escalationRequired()) {
            return Optional.empty();
        }
        if (assessment.finalRisk() == RiskLevel.LOW
                && candidate.type() != ResponsePlanStructuredOutput.ActionType.INVESTIGATE
                && candidate.type() != ResponsePlanStructuredOutput.ActionType.OBSERVE) {
            return Optional.empty();
        }
        if (assessment.finalRisk() == RiskLevel.LOW
                && candidate.urgency() != ActionUrgency.OBSERVATION) {
            return Optional.empty();
        }

        boolean hasRealEvidence = candidate.evidenceIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .anyMatch(usableEvidenceIds::contains);
        if (!hasRealEvidence) {
            return Optional.empty();
        }

        if (productionChange && !lowerAction.contains("人工确认")) {
            actionText = "经人工确认后，" + actionText;
        }
        return Optional.of(new ActionDraft(
                candidate.urgency(), actionText, OWNER_NAMES.get(candidate.ownerRole())));
    }

    /** 把紧急程度转换为稳定排序权重。 */
    private int urgencyRank(ActionUrgency urgency) {
        return switch (urgency) {
            case IMMEDIATE -> 0;
            case SHORT_TERM -> 1;
            case OBSERVATION -> 2;
        };
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
