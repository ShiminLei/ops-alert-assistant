package com.enterprise.opsassistant.domain;

/**
 * 分析阶段同时用于内部编排和后续 SSE 进度事件。
 *
 * <p>SupervisorAgent 每完成一步就发布对应阶段。简单前端可通过 SSE 实时显示进度，
 * 调用链日志也可以据此定位失败发生在哪一个环节。</p>
 */
public enum AnalysisStage {
    /** 服务端已经收到用户告警文本。 */
    RECEIVED,
    /** 自然语言已转换成 {@link AlertRecognition}。 */
    ALERT_RECOGNIZED,
    /** 已根据告警类型决定需要调用的运维工具。 */
    TOOLS_PLANNED,
    /** 工具调用结束，证据已经汇总。 */
    EVIDENCE_COLLECTED,
    /** 已基于证据生成根因候选和风险判断。 */
    ROOT_CAUSE_ANALYZED,
    /** 主模型或备用模型已完成基于规则结论的安全复核。 */
    AI_REVIEWED,
    /** 最终结构化报告和 Markdown 报告已经生成。 */
    REPORT_GENERATED,
    /** 整条分析链路正常完成。 */
    COMPLETED,
    /** 分析链路无法继续；具体异常原因由错误事件和日志记录。 */
    FAILED
}
