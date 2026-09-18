package com.enterprise.opsassistant.domain;

/**
 * 单次运维工具调用所产出证据的可用状态。
 *
 * <p>工具调用失败不等于整次分析必须失败。状态被保存在证据中，根因分析 Agent 可以降低
 * 相关结论的置信度，并在报告中明确说明证据缺口。</p>
 */
public enum EvidenceStatus {
    /** 工具正常返回，数据完整可用于判断。 */
    SUCCESS,
    /** 工具返回了部分数据，仍可参考但不能作为唯一依据。 */
    PARTIAL,
    /** 工具调用失败，没有获得可用数据。 */
    FAILED
}
