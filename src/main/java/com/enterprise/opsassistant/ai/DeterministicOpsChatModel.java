package com.enterprise.opsassistant.ai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 本地开发和自动化测试使用的确定性 Spring AI {@link ChatModel}。
 *
 * <p>它与真实模型实现遵守同一个 Spring AI 接口，因此上层仍然会完整经过 ChatClient、提示词、
 * Structured Output 和可观测性链路。区别只在最后一步：真实环境向远程模型发送请求，本地环境
 * 返回固定且可预测的 JSON。这样测试不会消耗模型额度，也不会因为网络波动而随机失败。</p>
 *
 * <p>这个类不是业务规则引擎，更不会伪装成真实智能推理。它只用于验证 Spring AI 集成链路；
 * 最终报告仍会把提供方标记为 mock，方便使用者辨认结果来源。</p>
 */
public class DeterministicOpsChatModel implements ChatModel, StreamingChatModel {

    private static final String REVIEW_JSON = """
            {
              "evidenceConsistency": "AI Mock 复核完成：告警和工具证据已进入 Spring AI 结构化复核链路",
              "mostLikelyRootCause": "以 Java 规则引擎和工具证据确定的根因候选为准",
              "evidenceGaps": ["真实模型未启用，本地 Mock 不补充新的事实判断"],
              "handlingNotes": ["最终风险不得低于 Java 规则结果", "所有生产变更必须人工确认"]
            }
            """;

    /**
     * 告警理解阶段的确定性响应。
     *
     * <p>Mock 模型不伪造语义推理，因此返回安全的未知值；AlertParserAgent 会将其与 Java
     * 基线合并。这个响应的作用是验证告警理解确实经过 ChatClient、主备路由和 Structured
     * Output，而不是让本地演示假装拥有真实大模型的理解能力。</p>
     */
    private static final String ALERT_UNDERSTANDING_JSON = """
            {
              "serviceName": "unknown-service",
              "alertType": "UNKNOWN",
              "abnormalMetrics": [],
              "initialRisk": "LOW",
              "userImpact": false,
              "escalationSuggested": false,
              "summary": "本地 Mock 已完成 Spring AI 告警理解，业务字段由 Java 安全基线补齐"
            }
            """;

    /**
     * 返回符合 {@link AiReviewStructuredOutput} 字段结构的 JSON。
     * Spring AI 的结构化输出转换器会把该 JSON 反序列化成 Java record。
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        AssistantMessage message = new AssistantMessage(responseFor(prompt));
        return new ChatResponse(List.of(new Generation(message)));
    }

    /**
     * 用多个确定性片段模拟远程模型的 token 流。
     *
     * <p>测试环境因此会真实经过 {@code ChatClient.stream()}、Reactor Flux、SSE 事件和最终 JSON
     * 转换链路，而不是为了测试绕回同步方法。片段刻意切在任意字符位置，验证业务代码不能假设
     * 单个流片段本身就是完整 JSON。</p>
     */
    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String json = responseFor(prompt);
        int firstBoundary = json.length() / 3;
        int secondBoundary = firstBoundary * 2;
        return Flux.just(
                responseChunk(json.substring(0, firstBoundary)),
                responseChunk(json.substring(firstBoundary, secondBoundary)),
                responseChunk(json.substring(secondBoundary))
        );
    }

    /** 根据提示词中的稳定任务标记选择对应的结构化 Mock 响应。 */
    private String responseFor(Prompt prompt) {
        // 只检查本次调用最后一条用户消息，不能扫描整个 Prompt。连续追问时 Prompt 还包含历史
        // 阶段消息，扫描全部内容会让后续 AI 复核误命中上一轮的告警理解任务标记。
        String currentRequest = prompt.getLastUserOrToolResponseMessage().getText();
        return currentRequest.contains("[TASK:ALERT_UNDERSTANDING]")
                ? ALERT_UNDERSTANDING_JSON
                : REVIEW_JSON;
    }

    private ChatResponse responseChunk(String content) {
        return new ChatResponse(List.of(
                new Generation(new AssistantMessage(content))));
    }
}
