package com.enterprise.opsassistant.ai;

/**
 * Spring AI 主备路由成功后的结果。
 *
 * @param body Structured Output 转换后的强类型业务对象
 * @param provider 实际成功的模型提供方，而不是配置中期望调用的提供方
 * @param model 实际成功的模型名称
 * @param fallbackUsed 是否因为主模型失败而切换到了备用模型
 */
public record SpringAiRoutingResult<T>(
        T body,
        String provider,
        String model,
        boolean fallbackUsed) {

    public SpringAiRoutingResult {
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
    }
}
