package com.enterprise.opsassistant.domain;

/**
 * 风险等级按声明顺序从低到高排列。
 *
 * <p>{@link #atLeast(RiskLevel)} 和 {@link #max(RiskLevel, RiskLevel)} 会使用枚举声明顺序进行比较，
 * 因此新增等级时必须继续保持从低到高排列。</p>
 */
public enum RiskLevel {
    /** 轻微异常，不影响核心功能，通常只需观察。 */
    LOW,
    /** 已出现明显异常，但影响范围有限，需要安排处理。 */
    MEDIUM,
    /** 核心能力受损或影响持续扩大，需要立即处置。 */
    HIGH,
    /** 大面积不可用、严重数据风险或核心业务中断，需要最高级别响应。 */
    CRITICAL;

    /**
     * 判断当前风险是否不低于指定风险。
     *
     * @param other 用来比较的最低风险等级
     * @return 当前等级等于或高于 other 时返回 true
     */
    public boolean atLeast(RiskLevel other) {
        return ordinal() >= other.ordinal();
    }

    /**
     * 返回两个风险等级中更高的一个，常用于合并规则判断与 AI 判断的结果。
     *
     * @param first 第一个风险等级
     * @param second 第二个风险等级
     * @return 两者中风险更高的等级
     */
    public static RiskLevel max(RiskLevel first, RiskLevel second) {
        return first.atLeast(second) ? first : second;
    }
}
