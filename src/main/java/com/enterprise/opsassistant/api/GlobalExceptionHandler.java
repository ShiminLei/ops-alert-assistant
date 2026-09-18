package com.enterprise.opsassistant.api;

import com.enterprise.opsassistant.exception.AnalysisExecutionException;
import com.enterprise.opsassistant.exception.InvalidAlertException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 Controller 层抛出的异常转换成统一、安全的 HTTP 错误响应。
 *
 * <p>异常的详细堆栈只写服务端日志。客户端只收到稳定错误码、简短说明和可用于排查的
 * analysisId，避免泄漏类名、服务器路径、数据库信息或第三方接口细节。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 处理 Bean Validation 产生的字段错误，例如 alertText 为空或超过长度限制。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ApiErrorResponse response = error(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "请求参数校验失败",
                null,
                request.getRequestURI(),
                fieldErrors
        );
        return ResponseEntity.badRequest().body(response);
    }

    /** 处理通过 HTTP 校验、但被业务输入边界拒绝的告警文本。 */
    @ExceptionHandler(InvalidAlertException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidAlert(
            InvalidAlertException exception,
            HttpServletRequest request) {
        ApiErrorResponse response = error(
                HttpStatus.BAD_REQUEST,
                "INVALID_ALERT",
                exception.getMessage(),
                null,
                request.getRequestURI(),
                Map.of()
        );
        return ResponseEntity.badRequest().body(response);
    }

    /**
     * 处理 SupervisorAgent 包装后的内部执行错误。
     * 对外隐藏 cause，只提供 analysisId 和通用说明。
     */
    @ExceptionHandler(AnalysisExecutionException.class)
    public ResponseEntity<ApiErrorResponse> handleAnalysisFailure(
            AnalysisExecutionException exception,
            HttpServletRequest request) {
        log.error("告警分析 API 执行失败: analysisId={}", exception.getAnalysisId(), exception);
        ApiErrorResponse response = error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "ANALYSIS_FAILED",
                "告警分析暂时失败，请使用 analysisId 查询日志或稍后重试",
                exception.getAnalysisId(),
                request.getRequestURI(),
                Map.of()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * 捕获未预料异常作为最后防线，防止 Spring 默认错误页或内部异常内容直接暴露。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        log.error("未处理的 API 异常: path={}", request.getRequestURI(), exception);
        ApiErrorResponse response = error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "服务暂时不可用，请稍后重试",
                null,
                request.getRequestURI(),
                Map.of()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /** 集中构造错误响应，保证所有处理方法使用相同字段和时间格式。 */
    private ApiErrorResponse error(HttpStatus status, String code, String message,
                                   String analysisId, String path, Map<String, String> fieldErrors) {
        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                code,
                message,
                analysisId,
                path,
                fieldErrors
        );
    }
}
