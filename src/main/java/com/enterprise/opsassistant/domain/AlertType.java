package com.enterprise.opsassistant.domain;

/**
 * 告警经过识别后的标准类型，避免后续流程依赖不稳定的自然语言描述。
 *
 * <p>同一种故障可能被描述成“接口卡住”“请求超时”或“响应太慢”。识别 Agent 将这些说法
 * 映射为有限枚举后，工具规划器便能稳定决定要调用哪些工具。</p>
 */
public enum AlertType {
    /** API 请求超时或延迟明显超过服务目标。 */
    API_TIMEOUT,
    /** 请求错误率在短时间内异常升高。 */
    ERROR_RATE_SPIKE,
    /** CPU 使用率持续高于安全阈值。 */
    HIGH_CPU,
    /** 内存使用率过高，可能伴随频繁 GC 或 OOM 风险。 */
    HIGH_MEMORY,
    /** 数据库连接失败、连接池耗尽或数据库响应异常。 */
    DATABASE_CONNECTION,
    /** 下游服务、中间件或第三方依赖不可用。 */
    DEPENDENCY_FAILURE,
    /** 新版本发布后出现的回归故障，通常需要重点评估回滚。 */
    POST_DEPLOYMENT_FAILURE,
    /** 信息不足或不属于已有分类；后续仍可通过通用工具继续调查。 */
    UNKNOWN
}
