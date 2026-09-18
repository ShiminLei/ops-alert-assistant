package com.enterprise.opsassistant.ai;

import java.util.Objects;

/**
 * 主备路由完成后的结果。
 *
 * @param response 实际成功模型的响应
 * @param fallbackUsed 是否因为主模型失败而切换到了备用模型
 */
public record AiRoutingResult(AiChatResponse response, boolean fallbackUsed) {

    public AiRoutingResult {
        response = Objects.requireNonNull(response, "response must not be null");
    }
}
