package com.enterprise.opsassistant.ai;

/**
 * 所有模型客户端共同遵守的最小接口。
 *
 * <p>业务 Agent 只依赖该接口，不直接依赖 DeepSeek、百炼或 WebClient。新增模型厂商时只需增加
 * 一个实现并注册到客户端仓库，路由与业务代码无需改变。</p>
 */
public interface AiChatClient {

    /** @return 与配置键一致的提供方名称 */
    String providerName();

    /** @return 用于报告和调用日志的模型名称 */
    String modelName();

    /** @param request 厂商无关请求 @return 标准模型响应 */
    AiChatResponse chat(AiChatRequest request);
}
