package com.enterprise.opsassistant.domain;

/**
 * 处置动作的紧急程度。
 *
 * <p>根因分析完成后，系统可能同时给出止损、修复和观察类建议。这个枚举用于把建议分组，
 * 让前端和运维人员能够优先执行最紧急的动作，而不是只依赖自然语言顺序。</p>
 */
public enum ActionUrgency {
    /** 必须立即执行的止损动作，例如回滚、摘除故障实例或切断异常流量。 */
    IMMEDIATE,

    /** 当前故障稳定后应尽快完成的修复动作，例如修改配置或补充容量。 */
    SHORT_TERM,

    /** 暂不变更系统，只需持续观察指标和日志的动作。 */
    OBSERVATION
}
