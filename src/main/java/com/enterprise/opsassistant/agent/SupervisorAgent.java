package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.ai.AiReviewResult;
import com.enterprise.opsassistant.ai.AiReviewStreamEvent;
import com.enterprise.opsassistant.ai.OpsAnalysisAiService;
import com.enterprise.opsassistant.domain.AnalysisProgressEvent;
import com.enterprise.opsassistant.domain.AnalysisSectionEvent;
import com.enterprise.opsassistant.domain.AnalysisSectionType;
import com.enterprise.opsassistant.domain.AnalysisStage;
import com.enterprise.opsassistant.domain.IncidentReport;
import com.enterprise.opsassistant.exception.AnalysisExecutionException;
import com.enterprise.opsassistant.exception.InvalidAlertException;
import com.enterprise.opsassistant.observability.OpsAssistantMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 企业运维告警分析的总编排 Agent。
 *
 * <p>SupervisorAgent 自身不解析文本、不查询工具、不判断根因，也不编写处置动作。它只负责按
 * 固定顺序协调专业 Agent，并将各阶段结果组装成最终 {@link IncidentReport}。这种编排方式
 * 体现了加分项中的多 Agent 分工，同时避免一个超大类混合所有业务职责。</p>
 *
 * <p>每次调用都会生成 analysisId，并写入日志 MDC 的 traceId。EvidenceCollectorAgent 等下游
 * 组件无需重复传递日志字段，其日志会自动带上相同编号。方法结束后会恢复原 MDC，防止线程复用
 * 时把上一请求的编号带到下一请求。</p>
 */
@Component
public class SupervisorAgent {

    private static final Logger log = LoggerFactory.getLogger(SupervisorAgent.class);

    private final AlertParserAgent alertParserAgent;
    private final ToolPlanningAgent toolPlanningAgent;
    private final EvidenceCollectorAgent evidenceCollectorAgent;
    private final RootCauseAgent rootCauseAgent;
    private final ResponsePlanAgent responsePlanAgent;
    private final OpsAnalysisAiService opsAnalysisAiService;
    private final OpsAssistantMetrics metrics;

    /** 所有专业 Agent 和 AI Service 都通过构造器注入，使依赖明确且便于测试替换。 */
    @Autowired
    public SupervisorAgent(AlertParserAgent alertParserAgent,
                           ToolPlanningAgent toolPlanningAgent,
                           EvidenceCollectorAgent evidenceCollectorAgent,
                           RootCauseAgent rootCauseAgent,
                           ResponsePlanAgent responsePlanAgent,
                           OpsAnalysisAiService opsAnalysisAiService,
                           OpsAssistantMetrics metrics) {
        this.alertParserAgent = alertParserAgent;
        this.toolPlanningAgent = toolPlanningAgent;
        this.evidenceCollectorAgent = evidenceCollectorAgent;
        this.rootCauseAgent = rootCauseAgent;
        this.responsePlanAgent = responsePlanAgent;
        this.opsAnalysisAiService = opsAnalysisAiService;
        this.metrics = metrics;
    }

    /** 不启动 Spring 但需要显式提供 AI Service 的测试便捷构造器。 */
    public SupervisorAgent(AlertParserAgent alertParserAgent,
                           ToolPlanningAgent toolPlanningAgent,
                           EvidenceCollectorAgent evidenceCollectorAgent,
                           RootCauseAgent rootCauseAgent,
                           ResponsePlanAgent responsePlanAgent,
                           OpsAnalysisAiService opsAnalysisAiService) {
        this(alertParserAgent, toolPlanningAgent, evidenceCollectorAgent,
                rootCauseAgent, responsePlanAgent, opsAnalysisAiService, OpsAssistantMetrics.noOp());
    }

    /**
     * 规则 Agent 单元测试使用的便捷构造器。它显式关闭 AI，不会创建隐藏网络依赖。
     */
    public SupervisorAgent(AlertParserAgent alertParserAgent,
                           ToolPlanningAgent toolPlanningAgent,
                           EvidenceCollectorAgent evidenceCollectorAgent,
                           RootCauseAgent rootCauseAgent,
                           ResponsePlanAgent responsePlanAgent) {
        this(alertParserAgent, toolPlanningAgent, evidenceCollectorAgent,
                rootCauseAgent, responsePlanAgent, OpsAnalysisAiService.ruleOnly());
    }

    /**
     * 同步完成一次分析，不订阅中间阶段事件。
     * 适合普通 REST JSON 接口和应用内部调用。
     *
     * @param rawAlert 用户输入的自然语言告警
     * @return 完整结构化事故报告
     */
    public IncidentReport analyze(String rawAlert) {
        return analyze(rawAlert, null, event -> {
            // 同步调用方不需要消费事件；阶段仍会写入统一日志。
        });
    }

    /**
     * 同步完成一次带 Chat Memory 的分析。会话编号为空时会自动开启新会话。
     */
    public IncidentReport analyze(String rawAlert, String conversationId) {
        return analyze(rawAlert, conversationId, event -> {
            // 普通 JSON 调用方只需要最终报告。
        });
    }

    /**
     * 完成一次分析，并把每个阶段事件交给 observer。
     *
     * <p>observer 是 Java 标准 Consumer，当前测试可用 List::add 收集事件，后续 SSE 控制器可用
     * emitter::send 推送事件。observer 自身失败不会中断核心分析，防止前端断开连接影响报告生成。</p>
     *
     * @param rawAlert 用户输入的自然语言告警
     * @param observer 阶段事件观察者；传入 null 等同于不订阅
     * @return 完整结构化事故报告
     */
    public IncidentReport analyze(String rawAlert, Consumer<AnalysisProgressEvent> observer) {
        return analyze(rawAlert, null, observer);
    }

    /**
     * 完成一次带会话上下文的分析，并把每个阶段事件交给 observer。
     *
     * @param rawAlert 当前轮的自然语言告警或追问
     * @param conversationId 可选会话编号；为空则生成新 UUID
     * @param observer 阶段事件观察者；传入 null 等同于不订阅
     * @return 包含 conversationId 的完整事故报告
     */
    public IncidentReport analyze(String rawAlert,
                                  String conversationId,
                                  Consumer<AnalysisProgressEvent> observer) {
        return analyze(rawAlert, conversationId, observer, event -> {
            // 普通同步调用只需要阶段或最终报告，不消费模型 token。
        });
    }

    /**
     * 完成一次分析，并分别发布确定性阶段事件与 Spring AI 模型流事件。
     *
     * <p>两个观察者刻意分开：阶段事件来自 Java 编排器，可以直接表达可靠的执行状态；模型流
     * 事件来自外部模型，只用于实时展示生成过程，不能替代最终的结构化转换和安全规则判断。</p>
     *
     * @param rawAlert 当前轮自然语言告警或追问
     * @param conversationId 可选会话编号；为空则生成新 UUID
     * @param observer Java 分析阶段观察者
     * @param aiStreamObserver AI 模型流式内容观察者
     * @return 完整且经过安全规则约束的事故报告
     */
    public IncidentReport analyze(String rawAlert,
                                  String conversationId,
                                  Consumer<AnalysisProgressEvent> observer,
                                  Consumer<AiReviewStreamEvent> aiStreamObserver) {
        return analyze(rawAlert, conversationId, observer, aiStreamObserver, event -> {
            // 同步调用方不需要逐块报告；完整 IncidentReport 仍由返回值提供。
        });
    }

    /**
     * 完成分析，并将阶段、模型 token 和已经确定的报告区段分别通知观察者。
     *
     * <p>区段事件只在对应业务对象完整生成后发送。例如一条 ToolEvidence 不会按字符拆分；模型
     * token 才使用细粒度增量。这样页面既能逐步呈现，又始终只渲染结构完整的业务数据。</p>
     */
    public IncidentReport analyze(String rawAlert,
                                  String conversationId,
                                  Consumer<AnalysisProgressEvent> observer,
                                  Consumer<AiReviewStreamEvent> aiStreamObserver,
                                  Consumer<AnalysisSectionEvent> sectionObserver) {
        long startedAt = System.nanoTime();
        String analysisId = UUID.randomUUID().toString();
        String effectiveConversationId = normalizeConversationId(conversationId);
        Consumer<AnalysisProgressEvent> safeObserver = observer == null ? event -> { } : observer;
        Consumer<AiReviewStreamEvent> safeAiStreamObserver =
                aiStreamObserver == null ? event -> { } : aiStreamObserver;
        Consumer<AnalysisSectionEvent> safeSectionObserver =
                sectionObserver == null ? event -> { } : sectionObserver;

        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", analysisId)) {
            emit(safeObserver, analysisId, AnalysisStage.RECEIVED, "已收到告警，开始分析");
            log.info("告警分析开始: analysisId={}", analysisId);

            try {
                // 使用同一个 conversationId 调用告警理解 AI，使本轮模型调用具有一致的会话身份。
                // AlertParserAgent 内部仍会先建立 Java 规则基线，模型失败时不会中断后续工具调查。
                var recognition = alertParserAgent.parse(rawAlert, effectiveConversationId);
                emit(safeObserver, analysisId, AnalysisStage.ALERT_RECOGNIZED,
                        "已识别服务、告警类型和初始风险");
                emitSection(safeSectionObserver, analysisId,
                        AnalysisSectionType.RECOGNITION, recognition);

                var toolPlan = toolPlanningAgent.plan(recognition);
                emit(safeObserver, analysisId, AnalysisStage.TOOLS_PLANNED,
                        "已规划 " + toolPlan.toolNames().size() + " 个运维工具");

                var evidenceCollection = evidenceCollectorAgent.collect(toolPlan);
                emit(safeObserver, analysisId, AnalysisStage.EVIDENCE_COLLECTED,
                        "已收集 " + evidenceCollection.evidence().size() + " 条工具证据");
                evidenceCollection.evidence().forEach(item -> emitSection(
                        safeSectionObserver, analysisId, AnalysisSectionType.EVIDENCE, item));

                var rootCause = rootCauseAgent.analyze(recognition, evidenceCollection);
                emit(safeObserver, analysisId, AnalysisStage.ROOT_CAUSE_ANALYZED,
                        "已完成根因候选和最终风险判断");
                emitSection(safeSectionObserver, analysisId,
                        AnalysisSectionType.ROOT_CAUSE, rootCause);

                var responsePlan = responsePlanAgent.plan(recognition, rootCause, evidenceCollection);
                responsePlan.actions().forEach(action -> emitSection(
                        safeSectionObserver, analysisId, AnalysisSectionType.ACTION, action));
                AiReviewResult aiReview = opsAnalysisAiService.review(
                        analysisId,
                        effectiveConversationId,
                        rawAlert,
                        recognition,
                        evidenceCollection,
                        rootCause,
                        responsePlan,
                        safeAiStreamObserver
                );
                var reviewedRootCause = appendAiReview(rootCause, aiReview);
                emit(safeObserver, analysisId, AnalysisStage.AI_REVIEWED,
                        aiReview.ruleOnly()
                                ? "AI 模型不可用或未启用，保留规则分析结果"
                                : "AI 模型已完成证据复核");
                emitSection(safeSectionObserver, analysisId,
                        AnalysisSectionType.AI_REVIEW, aiReview);
                IncidentReport report = new IncidentReport(
                        analysisId,
                        effectiveConversationId,
                        rawAlert.trim(),
                        recognition,
                        evidenceCollection.evidence(),
                        reviewedRootCause,
                        responsePlan.actions(),
                        responsePlan.followUpMetrics(),
                        aiReview.provider(),
                        aiReview.model(),
                        aiReview.fallbackUsed(),
                        Instant.now()
                );
                emit(safeObserver, analysisId, AnalysisStage.REPORT_GENERATED,
                        "结构化事故报告已经生成");
                emit(safeObserver, analysisId, AnalysisStage.COMPLETED,
                        "告警分析完成");
                log.info("告警分析完成: analysisId={}, service={}, risk={}, evidence={}, actions={}",
                        analysisId,
                        recognition.serviceName(),
                        reviewedRootCause.finalRisk(),
                        evidenceCollection.evidence().size(),
                        responsePlan.actions().size());
                metrics.recordAnalysisSuccess(
                        elapsedSince(startedAt),
                        reviewedRootCause.finalRisk(),
                        aiReview
                );
                return report;
            } catch (InvalidAlertException exception) {
                emit(safeObserver, analysisId, AnalysisStage.FAILED, "告警内容无效，分析终止");
                log.warn("告警输入无效: analysisId={}, reason={}", analysisId, exception.getMessage());
                metrics.recordAnalysisFailure(elapsedSince(startedAt), "invalid_alert");
                throw exception;
            } catch (RuntimeException exception) {
                emit(safeObserver, analysisId, AnalysisStage.FAILED, "分析过程中发生内部异常");
                log.error("告警分析失败: analysisId={}", analysisId, exception);
                metrics.recordAnalysisFailure(elapsedSince(startedAt), "internal_error");
                throw new AnalysisExecutionException(
                        analysisId,
                        "alert analysis failed, analysisId=" + analysisId,
                        exception
                );
            }
        }
    }

    /** 统一使用单调递增时钟计算分析耗时，不受系统时间校准影响。 */
    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }

    /** 首轮请求自动创建会话，后续轮次保留并规范化调用方传入的编号。 */
    private String normalizeConversationId(String conversationId) {
        return conversationId == null || conversationId.isBlank()
                ? UUID.randomUUID().toString()
                : conversationId.trim();
    }

    /**
     * 只把 AI 文本追加到推理说明，不允许模型改变 Java 已确定的风险、根因候选和安全决策。
     */
    private com.enterprise.opsassistant.domain.RootCauseAssessment appendAiReview(
            com.enterprise.opsassistant.domain.RootCauseAssessment rootCause,
            AiReviewResult aiReview) {
        if (aiReview.ruleOnly()) {
            return rootCause;
        }
        java.util.List<String> reasoning = new java.util.ArrayList<>(rootCause.reasoning());
        reasoning.add("AI 模型复核（" + aiReview.provider() + "/" + aiReview.model()
                + "）：" + aiReview.content());
        return new com.enterprise.opsassistant.domain.RootCauseAssessment(
                rootCause.finalRisk(),
                rootCause.candidates(),
                reasoning,
                rootCause.rollbackRecommended(),
                rootCause.escalationRequired(),
                rootCause.userImpact()
        );
    }

    /**
     * 创建、记录并通知一条阶段事件。
     * 观察者异常只记录警告，不允许破坏核心分析链路。
     */
    private void emit(Consumer<AnalysisProgressEvent> observer,
                      String analysisId,
                      AnalysisStage stage,
                      String message) {
        AnalysisProgressEvent event = new AnalysisProgressEvent(
                analysisId,
                stage,
                message,
                Instant.now()
        );
        log.info("分析阶段变化: analysisId={}, stage={}, message={}", analysisId, stage, message);
        try {
            observer.accept(event);
        } catch (RuntimeException observerException) {
            // 客户端断开是 SSE 场景中的常见情况。记录类型和原因即可，避免每个阶段重复打印长堆栈。
            log.warn("阶段事件观察者处理失败，不中断核心分析: analysisId={}, stage={}, reason={}",
                    analysisId, stage, observerException.toString());
        }
    }

    /**
     * 发布一块已经完成的报告数据；展示通道失败只记录告警，不反向破坏核心分析和最终报告。
     */
    private void emitSection(Consumer<AnalysisSectionEvent> observer,
                             String analysisId,
                             AnalysisSectionType section,
                             Object data) {
        AnalysisSectionEvent event = new AnalysisSectionEvent(
                analysisId, section, data, Instant.now());
        try {
            observer.accept(event);
        } catch (RuntimeException observerException) {
            log.warn("报告区段观察者处理失败，不中断核心分析: analysisId={}, section={}, reason={}",
                    analysisId, section, observerException.toString());
        }
    }
}
