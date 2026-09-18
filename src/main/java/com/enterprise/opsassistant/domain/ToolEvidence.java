package com.enterprise.opsassistant.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 一次运维工具调用形成的标准证据。
 *
 * <p>所有工具无论查询服务状态、错误日志、发布记录、资源、依赖还是数据库连接，最终都转换为
 * 同一种证据结构。EvidenceCollectorAgent 因而可以统一汇总成功、部分成功和失败的调用，
 * RootCauseAgent 也不需要依赖每个工具的 Java 实现细节。</p>
 *
 * @param evidenceId 证据唯一编号，供根因候选引用并用于审计
 * @param toolName 产生证据的工具名称
 * @param serviceName 本次查询针对的服务
 * @param status 证据是否完整可用
 * @param summary 面向模型和人工阅读的简短结论
 * @param data 工具返回的结构化明细；不同工具可以保存不同键值
 * @param errorMessage 调用异常说明；成功时通常为 null
 * @param durationMs 工具调用耗时，单位毫秒，可用于链路日志和性能指标
 * @param collectedAt 证据采集时间
 */
public record ToolEvidence(
        String evidenceId,
        String toolName,
        String serviceName,
        EvidenceStatus status,
        String summary,
        Map<String, Object> data,
        String errorMessage,
        long durationMs,
        Instant collectedAt) {

    /**
     * 建立证据的数据约束。
     *
     * <p>结构化数据使用不可变 Map 快照，防止工具返回后被外部代码修改；错误信息会把空白字符串
     * 归一化为 null，便于 JSON 输出和调用方判断；耗时不允许为负数。</p>
     */
    public ToolEvidence {
        evidenceId = requireText(evidenceId, "evidenceId");
        toolName = requireText(toolName, "toolName");
        serviceName = requireText(serviceName, "serviceName");
        status = Objects.requireNonNull(status, "status must not be null");
        summary = Objects.requireNonNullElse(summary, "").trim();
        data = Map.copyOf(Objects.requireNonNullElse(data, Map.of()));
        errorMessage = normalizeNullable(errorMessage);
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        collectedAt = Objects.requireNonNullElseGet(collectedAt, Instant::now);
    }

    /**
     * 提供比直接比较枚举更易读的成功判断。
     *
     * @return 仅当证据状态为 {@link EvidenceStatus#SUCCESS} 时返回 true
     */
    public boolean successful() {
        return status == EvidenceStatus.SUCCESS;
    }

    /** 校验证据编号、工具名和服务名等必填文本。 */
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    /** 将可选文本的 null 或空白统一表示为 null，否则返回去除首尾空白后的内容。 */
    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
