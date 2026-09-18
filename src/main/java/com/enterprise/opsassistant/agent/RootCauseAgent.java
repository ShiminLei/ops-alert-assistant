package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.ai.RootCauseAiService;
import com.enterprise.opsassistant.ai.RootCauseStructuredOutput;
import com.enterprise.opsassistant.domain.AlertRecognition;
import com.enterprise.opsassistant.domain.AlertType;
import com.enterprise.opsassistant.domain.EvidenceCollectionResult;
import com.enterprise.opsassistant.domain.EvidenceStatus;
import com.enterprise.opsassistant.domain.RiskLevel;
import com.enterprise.opsassistant.domain.RootCauseAssessment;
import com.enterprise.opsassistant.domain.RootCauseCandidate;
import com.enterprise.opsassistant.domain.ToolEvidence;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 使用 Spring AI 和 Java 安全规则共同完成根因综合的 Agent。
 *
 * <p>根因分析的核心不是“看到一个异常词就下结论”，而是交叉验证。以数据库连接池耗尽为例：
 * 数据库工具显示连接数达到上限，日志出现连接超时，发布记录又显示刚刚调小连接池，这三类独立
 * 证据共同出现时，置信度才会明显提高。</p>
 *
 * <p>该类同时承担确定性的安全规则：大面积不可用、极高错误率、实例全部失效等情况必须提升
 * 风险等级；模型可以补充候选原因和解释，但不能把这些硬规则判断出的风险降低。</p>
 */
@Component
public class RootCauseAgent {

    private final RootCauseAiService rootCauseAiService;

    /** Spring 运行时注入真正调用 ChatClient 的根因分析服务。 */
    @Autowired
    public RootCauseAgent(RootCauseAiService rootCauseAiService) {
        this.rootCauseAiService = rootCauseAiService;
    }

    /** 离线领域测试使用纯规则模式，避免单元测试产生隐藏的模型或网络依赖。 */
    public RootCauseAgent() {
        this.rootCauseAiService = null;
    }

    /**
     * 基于告警识别和工具证据生成根因评估。
     *
     * @param recognition 告警文本解析得到的初始事实
     * @param collection 工具计划及其实际执行结果
     * @return 包含候选根因、推理、风险、回滚与升级建议的评估
     */
    public RootCauseAssessment analyze(AlertRecognition recognition, EvidenceCollectionResult collection) {
        return analyze(recognition, collection, null);
    }

    /**
     * 在当前会话中完成根因综合：先建立确定性规则基线，再吸收经过证据校验的 AI 候选。
     *
     * <p>风险等级、回滚建议、升级判断和用户影响始终来自 Java 安全规则。AI 只扩充候选原因和
     * 推理说明，因此模型失败、幻觉或越权都不会削弱原有处置边界。</p>
     */
    public RootCauseAssessment analyze(AlertRecognition recognition,
                                       EvidenceCollectionResult collection,
                                       String conversationId) {
        if (recognition == null) {
            throw new IllegalArgumentException("recognition must not be null");
        }
        if (collection == null) {
            throw new IllegalArgumentException("collection must not be null");
        }
        if (!recognition.serviceName().equals(collection.plan().serviceName())) {
            throw new IllegalArgumentException("recognition and evidence must refer to the same service");
        }

        EvidenceContext context = buildContext(collection.evidence());
        List<RootCauseCandidate> candidates = buildCandidates(recognition, context);
        List<String> reasoning = buildReasoning(recognition, collection, context, candidates);
        RiskLevel finalRisk = calculateFinalRisk(recognition, context);
        boolean rollbackRecommended = shouldRollback(recognition, context);
        boolean escalationRequired = shouldEscalate(recognition, collection, finalRisk);
        String userImpact = describeUserImpact(recognition, context);

        RootCauseAssessment ruleAssessment = new RootCauseAssessment(
                finalRisk,
                candidates,
                reasoning,
                rollbackRecommended,
                escalationRequired,
                userImpact
        );

        if (rootCauseAiService == null
                || conversationId == null
                || conversationId.isBlank()) {
            return ruleAssessment;
        }

        return rootCauseAiService.analyze(conversationId, recognition, collection)
                .map(candidate -> mergeAiAssessment(ruleAssessment, collection, candidate))
                .orElse(ruleAssessment);
    }

    /**
     * 将模型候选合并进规则评估，但只接受真实、非失败证据编号支持的内容。
     *
     * <p>置信度还会按照有效证据数量设置上限：单证据最多 0.65、双证据最多 0.80、三项及以上
     * 最多 0.95。这个约束避免模型仅凭一条日志就声称拥有接近确定的结论。</p>
     */
    private RootCauseAssessment mergeAiAssessment(
            RootCauseAssessment ruleAssessment,
            EvidenceCollectionResult collection,
            RootCauseStructuredOutput output) {
        Set<String> usableEvidenceIds = collection.evidence().stream()
                .filter(item -> item.status() != EvidenceStatus.FAILED)
                .map(ToolEvidence::evidenceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        List<RootCauseCandidate> validAiCandidates = output.candidates().stream()
                .map(candidate -> validateAiCandidate(candidate, usableEvidenceIds))
                .flatMap(Optional::stream)
                .toList();
        if (validAiCandidates.isEmpty()) {
            return ruleAssessment;
        }

        // 规则候选与 AI 候选都保留；使用描述去重，防止模型重复输出占满报告。
        Map<String, RootCauseCandidate> uniqueCandidates = new LinkedHashMap<>();
        java.util.stream.Stream.concat(
                        ruleAssessment.candidates().stream(), validAiCandidates.stream())
                .forEach(candidate -> uniqueCandidates.merge(
                        candidate.description().toLowerCase(Locale.ROOT),
                        candidate,
                        (left, right) -> left.confidence() >= right.confidence() ? left : right));
        List<RootCauseCandidate> mergedCandidates = uniqueCandidates.values().stream()
                .sorted(Comparator.comparingDouble(RootCauseCandidate::confidence).reversed())
                .toList();

        List<String> reasoning = new ArrayList<>(ruleAssessment.reasoning());
        output.reasoning().stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .distinct()
                .limit(5)
                .map(item -> "AI 基于已校验证据的补充推理：" + item)
                .forEach(reasoning::add);
        reasoning.add("Java 已校验 AI 根因引用，本轮接受 "
                + validAiCandidates.size() + " 个有真实证据支撑的模型候选");

        return new RootCauseAssessment(
                ruleAssessment.finalRisk(),
                mergedCandidates,
                reasoning,
                ruleAssessment.rollbackRecommended(),
                ruleAssessment.escalationRequired(),
                ruleAssessment.userImpact());
    }

    /** 把单个不可信模型候选转换为满足领域约束的 RootCauseCandidate。 */
    private Optional<RootCauseCandidate> validateAiCandidate(
            RootCauseStructuredOutput.Candidate candidate,
            Set<String> usableEvidenceIds) {
        if (candidate == null
                || candidate.description() == null
                || candidate.description().isBlank()
                || candidate.confidence() == null
                || !Double.isFinite(candidate.confidence())
                || candidate.confidence() < 0
                || candidate.confidence() > 1) {
            return Optional.empty();
        }

        List<String> validIds = candidate.evidenceIds().stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .filter(usableEvidenceIds::contains)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        if (validIds.isEmpty()) {
            return Optional.empty();
        }

        double evidenceLimit = validIds.size() >= 3 ? 0.95 : validIds.size() == 2 ? 0.80 : 0.65;
        return Optional.of(new RootCauseCandidate(
                candidate.description().trim(),
                Math.min(candidate.confidence(), evidenceLimit),
                validIds));
    }

    /**
     * 从统一 ToolEvidence 中提取规则判断需要的事实。
     * 使用 Optional 表达“工具没执行或没有可用数据”，避免把缺失数据误当成零值或健康状态。
     */
    private EvidenceContext buildContext(List<ToolEvidence> evidence) {
        Optional<ToolEvidence> service = findUsableEvidence(evidence, "service-status");
        Optional<ToolEvidence> logs = findUsableEvidence(evidence, "error-log");
        Optional<ToolEvidence> deployment = findUsableEvidence(evidence, "deployment");
        Optional<ToolEvidence> resources = findUsableEvidence(evidence, "resource-usage");
        Optional<ToolEvidence> dependencies = findUsableEvidence(evidence, "dependency-status");
        Optional<ToolEvidence> database = findUsableEvidence(evidence, "database-connection");

        boolean databaseSaturated = database
                .map(item -> "SATURATED".equalsIgnoreCase(text(item, "state"))
                        || number(item, "activeConnections") >= number(item, "maxConnections")
                        || number(item, "waitingThreads") > 0)
                .orElse(false);
        boolean connectionErrorsInLogs = logs
                .map(item -> containsAny(searchableText(item),
                        "connection is not available", "sqltransientconnectionexception", "连接池", "连接超时"))
                .orElse(false);
        boolean connectionPoolChanged = deployment
                .map(item -> containsAny(searchableText(item),
                        "连接池", "maximum-pool-size", "maxconnections"))
                .orElse(false);
        boolean dependencyAbnormal = dependencies
                .map(item -> number(item, "abnormalCount") > 0)
                .orElse(false);
        boolean resourceAbnormal = resources
                .map(item -> number(item, "cpuPercent") >= 80
                        || number(item, "memoryPercent") >= 85
                        || number(item, "errorRatePercent") >= 5
                        || number(item, "p99LatencyMs") >= 1000)
                .orElse(false);

        return new EvidenceContext(
                service,
                logs,
                deployment,
                resources,
                dependencies,
                database,
                databaseSaturated,
                connectionErrorsInLogs,
                connectionPoolChanged,
                dependencyAbnormal,
                resourceAbnormal
        );
    }

    /**
     * 建立根因候选，并按置信度从高到低排列。
     * 每个候选只引用实际支持它的 evidenceId，报告使用者可以沿编号回查原始数据。
     */
    private List<RootCauseCandidate> buildCandidates(AlertRecognition recognition, EvidenceContext context) {
        List<RootCauseCandidate> candidates = new ArrayList<>();

        if (context.databaseSaturated()) {
            List<String> ids = evidenceIds(context.database(), context.logs(), context.deployment());
            double confidence = 0.75;
            if (context.connectionErrorsInLogs()) {
                confidence += 0.10;
            }
            if (context.connectionPoolChanged()) {
                confidence += 0.10;
            }
            candidates.add(new RootCauseCandidate(
                    "数据库连接池容量耗尽，业务线程无法及时取得连接",
                    Math.min(confidence, 0.95),
                    ids
            ));
        }

        boolean postDeploymentIncident = recognition.alertType() == AlertType.POST_DEPLOYMENT_FAILURE;
        if (context.deployment().isPresent()
                && (postDeploymentIncident || context.connectionPoolChanged())
                && (context.databaseSaturated() || context.resourceAbnormal())) {
            candidates.add(new RootCauseCandidate(
                    "最近一次发布变更可能触发了本次故障",
                    context.connectionPoolChanged() ? 0.90 : 0.75,
                    evidenceIds(context.deployment(), context.database(), context.resources())
            ));
        }

        if (context.dependencyAbnormal()) {
            candidates.add(new RootCauseCandidate(
                    "下游依赖响应异常放大了请求延迟和失败率",
                    0.65,
                    evidenceIds(context.dependencies(), context.resources(), context.logs())
            ));
        }

        if (context.resourceAbnormal()) {
            candidates.add(new RootCauseCandidate(
                    "服务资源或请求指标异常，是故障影响扩大的直接表现",
                    0.60,
                    evidenceIds(context.resources(), context.service())
            ));
        }

        if (candidates.isEmpty()) {
            candidates.add(new RootCauseCandidate(
                    "现有证据尚不足以确认明确根因，需要继续观察或补充数据",
                    0.20,
                    evidenceIds(context.service(), context.logs(), context.resources())
            ));
        }

        return candidates.stream()
                .sorted(Comparator.comparingDouble(RootCauseCandidate::confidence).reversed())
                .toList();
    }

    /** 生成可供人工复核的推理步骤，不只输出最终结论。 */
    private List<String> buildReasoning(AlertRecognition recognition,
                                        EvidenceCollectionResult collection,
                                        EvidenceContext context,
                                        List<RootCauseCandidate> candidates) {
        List<String> reasoning = new ArrayList<>();
        reasoning.add("原始告警被识别为 " + recognition.alertType()
                + "，初始风险为 " + recognition.initialRisk());
        reasoning.add("计划调用 " + collection.plan().toolNames().size() + " 个工具，获得 "
                + collection.successCount() + " 条完整证据、"
                + collection.partialCount() + " 条部分证据和 "
                + collection.failureCount() + " 条失败证据");

        if (context.databaseSaturated()) {
            reasoning.add("数据库连接数达到容量上限或存在等待线程，说明连接池已经饱和");
        }
        if (context.connectionErrorsInLogs()) {
            reasoning.add("错误日志出现数据库连接获取超时，与连接池饱和现象相互印证");
        }
        if (context.connectionPoolChanged()) {
            reasoning.add("最近发布记录包含连接池容量调整，变更时间与故障出现具有相关性");
        }
        if (context.dependencyAbnormal()) {
            reasoning.add("至少一个下游依赖处于非健康状态，可能造成额外延迟或级联失败");
        }
        reasoning.add("最终形成 " + candidates.size() + " 个根因候选，并按证据置信度排序");
        return List.copyOf(reasoning);
    }

    /**
     * 用硬规则计算最终风险，并确保最终风险绝不低于告警解析得到的初始风险。
     */
    private RiskLevel calculateFinalRisk(AlertRecognition recognition, EvidenceContext context) {
        RiskLevel risk = recognition.initialRisk();

        double errorRate = context.resources().map(item -> number(item, "errorRatePercent")).orElse(0.0);
        double availability = context.service().map(item -> number(item, "availabilityPercent")).orElse(100.0);
        double healthyInstances = context.service().map(item -> number(item, "healthyInstances")).orElse(1.0);
        String serviceState = context.service().map(item -> text(item, "state")).orElse("UNKNOWN");

        if (context.databaseSaturated() || errorRate >= 10 || "DEGRADED".equalsIgnoreCase(serviceState)) {
            risk = RiskLevel.max(risk, RiskLevel.HIGH);
        }
        if (errorRate >= 30 || availability < 30 || healthyInstances == 0) {
            risk = RiskLevel.CRITICAL;
        }
        if (context.dependencyAbnormal()) {
            risk = RiskLevel.max(risk, RiskLevel.MEDIUM);
        }
        return risk;
    }

    /**
     * 只有“发布相关告警 + 明确异常 + 可疑变更”同时成立时才建议回滚，
     * 避免看到任何故障都机械回滚。
     */
    private boolean shouldRollback(AlertRecognition recognition, EvidenceContext context) {
        boolean relatedToDeployment = recognition.alertType() == AlertType.POST_DEPLOYMENT_FAILURE;
        boolean confirmedAbnormality = context.databaseSaturated() || context.resourceAbnormal();
        return relatedToDeployment
                && context.deployment().isPresent()
                && confirmedAbnormality
                && context.connectionPoolChanged();
    }

    /** 高风险用户影响、关键风险或高风险下的证据不足都必须升级人工。 */
    private boolean shouldEscalate(AlertRecognition recognition,
                                   EvidenceCollectionResult collection,
                                   RiskLevel finalRisk) {
        return recognition.escalationSuggested()
                || finalRisk == RiskLevel.CRITICAL
                || (finalRisk.atLeast(RiskLevel.HIGH) && recognition.userImpact())
                || (finalRisk.atLeast(RiskLevel.HIGH) && !collection.hasEnoughEvidence());
    }

    /** 根据用户陈述和指标证据生成保守的影响说明，不把“未知”写成“无影响”。 */
    private String describeUserImpact(AlertRecognition recognition, EvidenceContext context) {
        if (recognition.userImpact()) {
            double errorRate = context.resources().map(item -> number(item, "errorRatePercent")).orElse(0.0);
            return errorRate > 0
                    ? "已确认用户请求受到影响，观测错误率为 " + errorRate + "%"
                    : "原始告警已确认存在用户影响，具体范围仍需继续核实";
        }
        if (context.resourceAbnormal()) {
            return "指标已经异常，但尚未从原始告警确认具体用户影响范围";
        }
        return "尚未确认用户影响";
    }

    /** 只把成功或部分成功证据用于规则判断，FAILED 证据只参与完整度统计。 */
    private Optional<ToolEvidence> findUsableEvidence(List<ToolEvidence> evidence, String toolName) {
        return evidence.stream()
                .filter(item -> item.toolName().equals(toolName))
                .filter(item -> item.status() != EvidenceStatus.FAILED)
                .findFirst();
    }

    /** 安全读取数值；字段缺失或类型不正确时返回 0，调用方通过 Optional 区分工具是否存在。 */
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

    /** 安全读取文本字段，缺失时返回空字符串。 */
    private String text(ToolEvidence evidence, String key) {
        Object value = evidence.data().get(key);
        return value == null ? "" : value.toString();
    }

    /** 把摘要、结构化数据和错误信息合并成小写检索文本，用于兼容日志和变更描述。 */
    private String searchableText(ToolEvidence evidence) {
        return (evidence.summary() + " " + evidence.data() + " "
                + (evidence.errorMessage() == null ? "" : evidence.errorMessage()))
                .toLowerCase(Locale.ROOT);
    }

    /** 判断文本是否包含任一根因关键词。 */
    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 从若干可选证据中收集非重复 evidenceId，并保持参数顺序。 */
    @SafeVarargs
    private final List<String> evidenceIds(Optional<ToolEvidence>... items) {
        return java.util.Arrays.stream(items)
                .flatMap(Optional::stream)
                .map(ToolEvidence::evidenceId)
                .distinct()
                .toList();
    }

    /**
     * 根因规则所需的内部事实集合。
     * 这是 Agent 的实现细节，不作为 API 输出，避免最终领域模型依赖具体工具字段。
     */
    private record EvidenceContext(
            Optional<ToolEvidence> service,
            Optional<ToolEvidence> logs,
            Optional<ToolEvidence> deployment,
            Optional<ToolEvidence> resources,
            Optional<ToolEvidence> dependencies,
            Optional<ToolEvidence> database,
            boolean databaseSaturated,
            boolean connectionErrorsInLogs,
            boolean connectionPoolChanged,
            boolean dependencyAbnormal,
            boolean resourceAbnormal) {
    }
}
