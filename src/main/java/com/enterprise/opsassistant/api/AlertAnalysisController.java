package com.enterprise.opsassistant.api;

import com.enterprise.opsassistant.agent.SupervisorAgent;
import com.enterprise.opsassistant.domain.IncidentReport;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外提供告警智能分析能力的 REST Controller。
 *
 * <p>Controller 只负责 HTTP 协议层工作：接收和校验 JSON、调用 SupervisorAgent、返回结果。
 * 它不包含告警识别或根因分析规则，保证 Web 层和业务编排层职责分离。</p>
 */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertAnalysisController {

    private final SupervisorAgent supervisorAgent;

    /** 使用构造器注入总编排器，避免 Controller 直接依赖五个专业 Agent。 */
    public AlertAnalysisController(SupervisorAgent supervisorAgent) {
        this.supervisorAgent = supervisorAgent;
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
}
