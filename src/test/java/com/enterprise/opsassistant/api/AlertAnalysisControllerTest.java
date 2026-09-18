package com.enterprise.opsassistant.api;

import com.enterprise.opsassistant.ai.ChatMemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST API 的端到端测试。
 *
 * <p>测试启动真实 Spring 容器并通过 MockMvc 发起 HTTP 请求，内部会执行完整 SupervisorAgent
 * 和六个 Mock 工具，因此同时验证 JSON 协议、参数校验和业务编排能正确连接。</p>
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
class AlertAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatMemoryService chatMemory;

    /** 正常请求应返回结构化事故报告，并包含至少三条真实工具证据。 */
    @Test
    void shouldAnalyzeNaturalLanguageAlert() throws Exception {
        String requestBody = """
                {
                  "alertText": "支付服务刚发布后大量请求超时，错误率18.7%，用户支付失败"
                }
                """;

        mockMvc.perform(post("/api/v1/alerts/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysisId").isNotEmpty())
                .andExpect(jsonPath("$.conversationId").isNotEmpty())
                .andExpect(jsonPath("$.recognition.serviceName").value("payment-service"))
                .andExpect(jsonPath("$.recognition.alertType").value("POST_DEPLOYMENT_FAILURE"))
                .andExpect(jsonPath("$.evidence.length()").value(6))
                .andExpect(jsonPath("$.rootCause.finalRisk").value("HIGH"))
                .andExpect(jsonPath("$.rootCause.rollbackRecommended").value(true))
                .andExpect(jsonPath("$.recommendedActions.length()").isNotEmpty())
                .andExpect(jsonPath("$.modelProvider").value("mock-primary"))
                .andExpect(jsonPath("$.modelName").value("mock-ops-primary"))
                .andExpect(jsonPath("$.fallbackUsed").value(false))
                .andExpect(jsonPath("$.rootCause.reasoning[*]")
                        .value(org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("AI Mock 复核完成"))));

        // 真正访问 Prometheus 端点，确认业务指标不仅存在内存中，还能被监控系统抓取。
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "ops_assistant_analysis_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "ops_assistant_tool_calls_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "ops_assistant_ai_provider_calls_total")));
    }

    /** 首轮自动创建会话，第二轮携带同一编号后应复用并追加 Chat Memory。 */
    @Test
    void shouldContinueConversationWithChatMemory() throws Exception {
        MvcResult firstResult = mockMvc.perform(post("/api/v1/alerts/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "alertText": "支付服务发布后错误率18.7%，用户支付失败"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").isNotEmpty())
                .andReturn();
        JsonNode firstReport = objectMapper.readTree(firstResult.getResponse().getContentAsString());
        String conversationId = firstReport.path("conversationId").asText();
        String followUpRequest = objectMapper.writeValueAsString(java.util.Map.of(
                "alertText", "支付服务当前错误率降到2%，请继续复核是否恢复",
                "conversationId", conversationId
        ));

        mockMvc.perform(post("/api/v1/alerts/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(followUpRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversationId").value(conversationId));

        assertThat(chatMemory.history(conversationId))
                .extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT,
                        MessageType.USER, MessageType.ASSISTANT);
    }

    /** 空告警应在进入 SupervisorAgent 前被校验，并返回统一 400 错误结构。 */
    @Test
    void shouldReturnValidationErrorForBlankAlert() throws Exception {
        String requestBody = """
                {
                  "alertText": "   "
                }
                """;

        mockMvc.perform(post("/api/v1/alerts/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("请求参数校验失败"))
                .andExpect(jsonPath("$.path").value("/api/v1/alerts/analyze"))
                .andExpect(jsonPath("$.fieldErrors.alertText").value("告警内容不能为空"));
    }

    /**
     * 会话编号会作为内存索引使用，因此只接受字母、数字、下划线和短横线。
     * 在入口拒绝斜杠等特殊字符，也能避免编号被误当成路径或日志控制字符。
     */
    @Test
    void shouldReturnValidationErrorForInvalidConversationId() throws Exception {
        mockMvc.perform(post("/api/v1/alerts/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "alertText": "请继续分析支付服务告警",
                                  "conversationId": "conversation/../../invalid"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.conversationId")
                        .value("会话编号格式不正确"));
    }

    /** 流式接口应依次返回 progress 事件，并以包含完整报告的 report 事件结束。 */
    @Test
    void shouldStreamProgressAndFinalReport() throws Exception {
        String requestBody = """
                {
                  "alertText": "支付服务刚发布后大量请求超时，错误率18.7%，用户支付失败"
                }
                """;

        MvcResult initialResult = mockMvc.perform(post("/api/v1/alerts/analyze/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(requestBody))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completedResult = mockMvc.perform(asyncDispatch(initialResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String stream = completedResult.getResponse().getContentAsString();
        assertThat(stream)
                .contains("event:progress")
                .contains("\"stage\":\"RECEIVED\"")
                .contains("\"stage\":\"AI_REVIEWED\"")
                .contains("\"stage\":\"COMPLETED\"")
                .contains("event:report")
                .contains("\"serviceName\":\"payment-service\"")
                .contains("\"finalRisk\":\"HIGH\"");
        assertThat(stream.indexOf("\"stage\":\"RECEIVED\""))
                .isLessThan(stream.indexOf("event:report"));
    }

    /** Markdown 接口应返回可下载报告，并包含证据、根因、处置和人工确认声明。 */
    @Test
    void shouldDownloadMarkdownIncidentReport() throws Exception {
        String requestBody = """
                {
                  "alertText": "支付服务刚发布后大量请求超时，错误率18.7%，用户支付失败"
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/v1/alerts/analyze/markdown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("text/markdown")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("incident-report-")))
                .andReturn();

        String markdown = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(markdown)
                .contains("# 企业运维告警智能处置报告")
                .contains("## 4. 运维工具证据")
                .contains("数据库连接池容量耗尽")
                .contains("## 6. 建议处置动作")
                .contains("从 2.4.1 回滚到 2.4.0")
                .contains("生产回滚、扩容、限流和配置修改均需由有权限的人员确认");
    }
}
