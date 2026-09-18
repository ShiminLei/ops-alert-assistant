package com.enterprise.opsassistant.domain;

/**
 * 可以独立推送并逐块展示的事故报告区段。
 *
 * <p>枚举值用于 Java 分支判断，eventName 是稳定的 SSE 协议名称。两者分开后，即使以后调整
 * Java 命名，也不会无意中破坏已经接入的浏览器或其他客户端。</p>
 */
public enum AnalysisSectionType {
    RECOGNITION("recognition"),
    EVIDENCE("evidence"),
    ROOT_CAUSE("root-cause"),
    ACTION("action"),
    AI_REVIEW("ai-review");

    private final String eventName;

    AnalysisSectionType(String eventName) {
        this.eventName = eventName;
    }

    public String eventName() {
        return eventName;
    }
}
