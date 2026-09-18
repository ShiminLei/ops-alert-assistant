package com.enterprise.opsassistant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交自然语言告警的 HTTP 请求对象。
 *
 * <p>Controller 不直接接收裸 String，而是使用独立请求对象。这样将来增加 conversationId、
 * 环境或调用来源时，可以向 JSON 增加字段而不需要改变接口路径和请求体类型。</p>
 *
 * @param alertText 用户输入的自然语言告警；不能为空，最大 4000 字符以限制异常大请求
 */
public record AnalyzeAlertRequest(
        @NotBlank(message = "告警内容不能为空")
        @Size(max = 4000, message = "告警内容不能超过 4000 个字符")
        String alertText) {
}
