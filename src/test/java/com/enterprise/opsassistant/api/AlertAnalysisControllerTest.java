package com.enterprise.opsassistant.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
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
class AlertAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
                .andExpect(jsonPath("$.recognition.serviceName").value("payment-service"))
                .andExpect(jsonPath("$.recognition.alertType").value("POST_DEPLOYMENT_FAILURE"))
                .andExpect(jsonPath("$.evidence.length()").value(6))
                .andExpect(jsonPath("$.rootCause.finalRisk").value("HIGH"))
                .andExpect(jsonPath("$.rootCause.rollbackRecommended").value(true))
                .andExpect(jsonPath("$.recommendedActions.length()").isNotEmpty())
                .andExpect(jsonPath("$.modelProvider").value("rule-engine"));
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
                .contains("\"stage\":\"COMPLETED\"")
                .contains("event:report")
                .contains("\"serviceName\":\"payment-service\"")
                .contains("\"finalRisk\":\"HIGH\"");
        assertThat(stream.indexOf("\"stage\":\"RECEIVED\""))
                .isLessThan(stream.indexOf("event:report"));
    }
}
