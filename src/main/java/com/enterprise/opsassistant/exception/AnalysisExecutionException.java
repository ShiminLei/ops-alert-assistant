package com.enterprise.opsassistant.exception;

/**
 * 表示分析编排过程中发生了无法在当前步骤恢复的内部异常。
 *
 * <p>该异常保留 analysisId，用户可以把编号提供给运维人员检索同一次调用链日志；原始 cause
 * 只记录在服务端，不应直接暴露到 API 响应。</p>
 */
public class AnalysisExecutionException extends RuntimeException {

    private final String analysisId;

    /**
     * @param analysisId 失败分析的唯一编号
     * @param message 面向系统日志的错误概述
     * @param cause 触发失败的原始异常
     */
    public AnalysisExecutionException(String analysisId, String message, Throwable cause) {
        super(message, cause);
        this.analysisId = analysisId;
    }

    /** @return 可用于关联日志的分析编号 */
    public String getAnalysisId() {
        return analysisId;
    }
}
