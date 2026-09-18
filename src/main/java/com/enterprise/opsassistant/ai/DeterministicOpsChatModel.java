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

    private static final String JSON = """
            {
              "evidenceConsistency": "AI Mock 复核完成：告警和工具证据已进入 Spring AI 结构化复核链路",
              "mostLikelyRootCause": "以 Java 规则引擎和工具证据确定的根因候选为准",
              "evidenceGaps": ["真实模型未启用，本地 Mock 不补充新的事实判断"],
              "handlingNotes": ["最终风险不得低于 Java 规则结果", "所有生产变更必须人工确认"]
            }
            """;

    /**
     * 返回符合 {@link AiReviewStructuredOutput} 字段结构的 JSON。
     * Spring AI 的结构化输出转换器会把该 JSON 反序列化成 Java record。
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        AssistantMessage message = new AssistantMessage(JSON);
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
        int firstBoundary = JSON.length() / 3;
        int secondBoundary = firstBoundary * 2;
        return Flux.just(
                responseChunk(JSON.substring(0, firstBoundary)),
                responseChunk(JSON.substring(firstBoundary, secondBoundary)),
                responseChunk(JSON.substring(secondBoundary))
        );
    }

    private ChatResponse responseChunk(String content) {
        return new ChatResponse(List.of(
                new Generation(new AssistantMessage(content))));
    }
}
