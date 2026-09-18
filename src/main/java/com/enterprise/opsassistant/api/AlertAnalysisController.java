package com.enterprise.opsassistant.api;

import com.enterprise.opsassistant.agent.SupervisorAgent;
import com.enterprise.opsassistant.domain.AnalysisProgressEvent;
import com.enterprise.opsassistant.domain.IncidentReport;
import com.enterprise.opsassistant.exception.AnalysisExecutionException;
import com.enterprise.opsassistant.exception.InvalidAlertException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对外提供告警智能分析能力的 REST Controller。
 *
 * <p>Controller 只负责 HTTP 协议层工作：接收和校验 JSON、调用 SupervisorAgent、返回结果。
 * 它不包含告警识别或根因分析规则，保证 Web 层和业务编排层职责分离。</p>
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertAnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AlertAnalysisController.class);

    /** SSE 最长保持 60 秒；实际模型接入后可改为配置项并与模型超时保持协调。 */
    private static final long SSE_TIMEOUT_MILLIS = 60_000L;

    private final SupervisorAgent supervisorAgent;
    private final Executor analysisTaskExecutor;

    /**
     * 使用构造器注入总编排器和专用线程池。
     * Qualifier 明确选择告警分析线程池，避免将来项目出现多个 Executor 时注入错误实现。
     */
    public AlertAnalysisController(
            SupervisorAgent supervisorAgent,
            @Qualifier("analysisTaskExecutor") Executor analysisTaskExecutor) {
        this.supervisorAgent = supervisorAgent;
        this.analysisTaskExecutor = analysisTaskExecutor;
    }

    /**
     * 同步分析一段自然语言告警并返回完整结构化报告。
     *
     * <p>{@link Valid} 会在进入方法前执行 AnalyzeAlertRequest 上的字段约束，空白或超长请求由
     * GlobalExceptionHandler 转换成统一的 HTTP 400 响应。</p>
     *
     * @param request 包含自然语言告警的 JSON 请求
     * @return 包含识别结果、工具证据、根因、处置动作和模型来源的事故报告
     */
    @PostMapping(
            path = "/analyze",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public IncidentReport analyze(@Valid @RequestBody AnalyzeAlertRequest request) {
        return supervisorAgent.analyze(request.alertText());
    }

    /**
     * 以 Server-Sent Events 形式实时返回分析进度和最终报告。
     *
     * <p>接口立即返回 SseEmitter，真正的分析在线程池中进行。事件名称固定为：</p>
     * <ul>
     *     <li>{@code progress}：AnalysisProgressEvent，可更新前端进度条；</li>
     *     <li>{@code report}：IncidentReport，表示分析成功结束；</li>
     *     <li>{@code error}：StreamErrorEvent，表示连接建立后的分析失败。</li>
     * </ul>
     *
     * @param request 包含自然语言告警的 JSON 请求
     * @return 已建立的 SSE 输出通道
     */
    @PostMapping(
            path = "/analyze/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter analyzeStream(@Valid @RequestBody AnalyzeAlertRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean clientConnected = new AtomicBoolean(true);
        AtomicReference<String> analysisId = new AtomicReference<>();

        // 三种结束回调都只更新连接状态；分析线程会自行结束或继续生成可审计报告。
        emitter.onCompletion(() -> clientConnected.set(false));
        emitter.onTimeout(() -> {
            clientConnected.set(false);
            log.warn("SSE 连接超时: analysisId={}", analysisId.get());
            emitter.complete();
        });
        emitter.onError(exception -> {
            clientConnected.set(false);
            log.warn("SSE 连接异常: analysisId={}, reason={}", analysisId.get(), exception.toString());
        });

        analysisTaskExecutor.execute(() -> runStreamingAnalysis(
                request.alertText(), emitter, clientConnected, analysisId));
        return emitter;
    }

    /**
     * 在线程池中执行 SupervisorAgent，并把阶段事件和最终报告写入 SSE。
     * HTTP 连接建立后的异常必须转换成 error 事件，不能再交给普通全局异常处理器。
     */
    private void runStreamingAnalysis(String alertText,
                                      SseEmitter emitter,
                                      AtomicBoolean clientConnected,
                                      AtomicReference<String> analysisId) {
        try {
            IncidentReport report = supervisorAgent.analyze(alertText, event -> {
                analysisId.compareAndSet(null, event.analysisId());
                sendProgress(emitter, clientConnected, event);
            });
            sendEvent(emitter, clientConnected, "report", report.analysisId(), report);
            completeIfConnected(emitter, clientConnected);
        } catch (InvalidAlertException exception) {
            sendStreamError(emitter, clientConnected, analysisId.get(),
                    "INVALID_ALERT", exception.getMessage());
        } catch (AnalysisExecutionException exception) {
            sendStreamError(emitter, clientConnected, exception.getAnalysisId(),
                    "ANALYSIS_FAILED", "告警分析暂时失败，请使用 analysisId 查询日志或稍后重试");
        } catch (RuntimeException exception) {
            log.error("SSE 告警分析发生未预期异常: analysisId={}", analysisId.get(), exception);
            sendStreamError(emitter, clientConnected, analysisId.get(),
                    "INTERNAL_ERROR", "服务暂时不可用，请稍后重试");
        }
    }

    /** 把 SupervisorAgent 的阶段事件统一发送为名为 progress 的 SSE 事件。 */
    private void sendProgress(SseEmitter emitter,
                              AtomicBoolean clientConnected,
                              AnalysisProgressEvent event) {
        sendEvent(emitter, clientConnected, "progress",
                event.analysisId() + ":" + event.stage(), event);
    }

    /** 发送安全错误事件并关闭 SSE 连接。 */
    private void sendStreamError(SseEmitter emitter,
                                 AtomicBoolean clientConnected,
                                 String analysisId,
                                 String code,
                                 String message) {
        StreamErrorEvent error = new StreamErrorEvent(
                analysisId,
                code,
                message,
                Instant.now()
        );
        sendEvent(emitter, clientConnected, "error",
                analysisId == null ? code : analysisId + ":error", error);
        completeIfConnected(emitter, clientConnected);
    }

    /**
     * 发送任意 SSE 数据。第一次 IOException 后立即把连接标记为断开，后续阶段不再重复发送。
     */
    private void sendEvent(SseEmitter emitter,
                           AtomicBoolean clientConnected,
                           String eventName,
                           String eventId,
                           Object data) {
        if (!clientConnected.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(eventId)
                    .name(eventName)
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException exception) {
            clientConnected.set(false);
            throw new StreamDeliveryException("failed to deliver SSE event", exception);
        }
    }

    /** 仅在连接仍有效时完成响应，并使用 compareAndSet 避免多个结束路径重复 complete。 */
    private void completeIfConnected(SseEmitter emitter, AtomicBoolean clientConnected) {
        if (clientConnected.compareAndSet(true, false)) {
            emitter.complete();
        }
    }

    /**
     * SSE 投递失败的 Controller 内部异常。
     * SupervisorAgent 会隔离观察者异常，因此该类型不会破坏核心分析。
     */
    private static class StreamDeliveryException extends RuntimeException {
        StreamDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
