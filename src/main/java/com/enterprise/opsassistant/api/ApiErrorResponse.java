package com.enterprise.opsassistant.api;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 所有 REST 接口统一使用的错误响应。
 *
 * <p>固定错误结构可以让前端不必针对不同异常猜测 JSON 格式。内部堆栈、API Key、数据库信息
 * 等敏感内容不会放入响应；如果分析已经生成 analysisId，调用方可用该编号联系运维检索日志。</p>
 *
 * @param timestamp 错误响应生成时间
 * @param status HTTP 状态码
 * @param code 稳定的机器可读错误码
 * @param message 面向用户的安全错误说明
 * @param analysisId 已进入分析链路时生成的编号；校验阶段失败时可能为 null
 * @param path 发生错误的请求路径
 * @param fieldErrors 字段名到校验说明的映射；非字段校验错误时为空 Map
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String analysisId,
        String path,
        Map<String, String> fieldErrors) {

    /** 对时间和字段错误集合建立安全默认值，并冻结 Map 防止返回后被修改。 */
    public ApiErrorResponse {
        timestamp = Objects.requireNonNullElseGet(timestamp, Instant::now);
        code = requireText(code, "code");
        message = requireText(message, "message");
        path = requireText(path, "path");
        fieldErrors = Map.copyOf(Objects.requireNonNullElse(fieldErrors, Map.of()));
    }

    /** 校验错误响应必填文本。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
