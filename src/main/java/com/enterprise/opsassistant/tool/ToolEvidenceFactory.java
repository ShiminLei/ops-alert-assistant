package com.enterprise.opsassistant.tool;

import com.enterprise.opsassistant.domain.EvidenceStatus;
import com.enterprise.opsassistant.domain.ToolEvidence;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 统一创建工具证据，避免六个工具重复拼装编号、耗时和时间戳。
 *
 * <p>这是包内辅助类，不暴露给 Agent。工具只关心业务数据，证据编号和计时等横切细节由这里处理。</p>
 */
final class ToolEvidenceFactory {

    /** 工具类，不需要实例化。 */
    private ToolEvidenceFactory() {
    }

    /** 创建一次成功工具调用的证据。 */
    static ToolEvidence success(String toolName, String serviceName, String summary,
                                Map<String, Object> data, long startedAtNanos) {
        return create(toolName, serviceName, EvidenceStatus.SUCCESS, summary, data, null, startedAtNanos);
    }

    /**
     * 创建未知服务对应的部分证据。
     * 不使用 FAILED，是因为工具本身正常工作，只是 Mock 数据源没有该服务。
     */
    static ToolEvidence serviceNotFound(String toolName, String serviceName, long startedAtNanos) {
        return create(
                toolName,
                normalizeServiceName(serviceName),
                EvidenceStatus.PARTIAL,
                "Mock 运维数据中未找到该服务",
                Map.of("dataAvailable", false),
                "unknown service: " + normalizeServiceName(serviceName),
                startedAtNanos
        );
    }

    /** 创建 ToolEvidence，并把纳秒计时转换成对外展示的毫秒耗时。 */
    private static ToolEvidence create(String toolName, String serviceName, EvidenceStatus status,
                                       String summary, Map<String, Object> data, String errorMessage,
                                       long startedAtNanos) {
        long elapsedNanos = Math.max(0, System.nanoTime() - startedAtNanos);
        long durationMs = elapsedNanos / 1_000_000;
        return new ToolEvidence(
                toolName + "-" + UUID.randomUUID(),
                toolName,
                normalizeServiceName(serviceName),
                status,
                summary,
                data,
                errorMessage,
                durationMs,
                Instant.now()
        );
    }

    /** 保证即使调用方传入空服务名，证据模型仍能给出可审计的明确值。 */
    private static String normalizeServiceName(String serviceName) {
        return serviceName == null || serviceName.isBlank() ? "unknown-service" : serviceName.trim();
    }
}
