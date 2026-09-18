package com.enterprise.opsassistant.ai;

/**
 * 一次 AI 流式复核尝试所处的阶段。
 *
 * <p>{@link #START} 不只是“模型开始工作”的提示，它还是客户端的缓冲区边界：同一 Provider
 * 发生重试，或者主模型失败后切换备用模型时，都会重新发送 START。前端收到它后必须清空上一
 * 次尝试留下的半截内容，避免把两个不同请求生成的 JSON 拼在一起。</p>
 */
public enum AiReviewStreamPhase {
    /** 开始一次新的模型调用尝试，调用方应重置当前流式文本。 */
    START,

    /** 模型新生成了一小段内容，调用方应按到达顺序追加。 */
    DELTA,

    /** 本次流已经完整接收，并且内容已经成功转换为强类型 Java 对象。 */
    COMPLETE
}
