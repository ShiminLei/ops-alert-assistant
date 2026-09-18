package com.enterprise.opsassistant.agent;

import com.enterprise.opsassistant.domain.AnalysisProgressEvent;
import com.enterprise.opsassistant.domain.AnalysisStage;
import com.enterprise.opsassistant.domain.IncidentReport;
import com.enterprise.opsassistant.exception.AnalysisExecutionException;
import com.enterprise.opsassistant.exception.InvalidAlertException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    /** 当前尚未连接外部模型，报告明确标记为确定性规则引擎，不能伪装成 AI 模型结果。 */
    private static final String RULE_PROVIDER = "rule-engine";
    private static final String RULE_MODEL = "deterministic-safety-model-v1";

    private final AlertParserAgent alertParserAgent;
    private final ToolPlanningAgent toolPlanningAgent;
    private final EvidenceCollectorAgent evidenceCollectorAgent;
    private final RootCauseAgent rootCauseAgent;
    private final ResponsePlanAgent responsePlanAgent;

    /** 所有专业 Agent 都通过构造器注入，使依赖明确且便于单元测试替换。 */
    public SupervisorAgent(AlertParserAgent alertParserAgent,
                           ToolPlanningAgent toolPlanningAgent,
                           EvidenceCollectorAgent evidenceCollectorAgent,
                           RootCauseAgent rootCauseAgent,
                           ResponsePlanAgent responsePlanAgent) {
        this.alertParserAgent = alertParserAgent;
        this.toolPlanningAgent = toolPlanningAgent;
        this.evidenceCollectorAgent = evidenceCollectorAgent;
        this.rootCauseAgent = rootCauseAgent;
        this.responsePlanAgent = responsePlanAgent;
    }

    /**
     * 同步完成一次分析，不订阅中间阶段事件。
     * 适合普通 REST JSON 接口和应用内部调用。
     *
     * @param rawAlert 用户输入的自然语言告警
     * @return 完整结构化事故报告
     */
    public IncidentReport analyze(String rawAlert) {
        return analyze(rawAlert, event -> {
            // 同步调用方不需要消费事件；阶段仍会写入统一日志。
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
        String analysisId = UUID.randomUUID().toString();
        Consumer<AnalysisProgressEvent> safeObserver = observer == null ? event -> { } : observer;

        try (MDC.MDCCloseable ignored = MDC.putCloseable("traceId", analysisId)) {
            emit(safeObserver, analysisId, AnalysisStage.RECEIVED, "已收到告警，开始分析");
            log.info("告警分析开始: analysisId={}", analysisId);

            try {
                var recognition = alertParserAgent.parse(rawAlert);
                emit(safeObserver, analysisId, AnalysisStage.ALERT_RECOGNIZED,
                        "已识别服务、告警类型和初始风险");

                var toolPlan = toolPlanningAgent.plan(recognition);
                emit(safeObserver, analysisId, AnalysisStage.TOOLS_PLANNED,
                        "已规划 " + toolPlan.toolNames().size() + " 个运维工具");

                var evidenceCollection = evidenceCollectorAgent.collect(toolPlan);
                emit(safeObserver, analysisId, AnalysisStage.EVIDENCE_COLLECTED,
                        "已收集 " + evidenceCollection.evidence().size() + " 条工具证据");

                var rootCause = rootCauseAgent.analyze(recognition, evidenceCollection);
                emit(safeObserver, analysisId, AnalysisStage.ROOT_CAUSE_ANALYZED,
                        "已完成根因候选和最终风险判断");

                var responsePlan = responsePlanAgent.plan(recognition, rootCause, evidenceCollection);
                IncidentReport report = new IncidentReport(
                        analysisId,
                        rawAlert.trim(),
                        recognition,
                        evidenceCollection.evidence(),
                        rootCause,
                        responsePlan.actions(),
                        responsePlan.followUpMetrics(),
                        RULE_PROVIDER,
                        RULE_MODEL,
                        false,
                        Instant.now()
                );
                emit(safeObserver, analysisId, AnalysisStage.REPORT_GENERATED,
                        "结构化事故报告已经生成");
                emit(safeObserver, analysisId, AnalysisStage.COMPLETED,
                        "告警分析完成");
                log.info("告警分析完成: analysisId={}, service={}, risk={}, evidence={}, actions={}",
                        analysisId,
                        recognition.serviceName(),
                        rootCause.finalRisk(),
                        evidenceCollection.evidence().size(),
                        responsePlan.actions().size());
                return report;
            } catch (InvalidAlertException exception) {
                emit(safeObserver, analysisId, AnalysisStage.FAILED, "告警内容无效，分析终止");
                log.warn("告警输入无效: analysisId={}, reason={}", analysisId, exception.getMessage());
                throw exception;
            } catch (RuntimeException exception) {
                emit(safeObserver, analysisId, AnalysisStage.FAILED, "分析过程中发生内部异常");
                log.error("告警分析失败: analysisId={}", analysisId, exception);
                throw new AnalysisExecutionException(
                        analysisId,
                        "alert analysis failed, analysisId=" + analysisId,
                        exception
                );
            }
        }
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
}
