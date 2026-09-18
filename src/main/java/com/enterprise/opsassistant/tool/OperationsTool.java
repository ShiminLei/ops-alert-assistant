package com.enterprise.opsassistant.tool;

import com.enterprise.opsassistant.domain.ToolEvidence;

/**
 * 所有只读运维查询工具共同遵守的接口。
 *
 * <p>上层 EvidenceCollectorAgent 只需要持有 {@code List<OperationsTool>}，即可按工具名选择并执行工具。
 * 这种面向接口的设计把 Agent 编排与具体数据来源解耦：当前实现读取 Mock 数据，以后可以替换为
 * Prometheus、ELK、Kubernetes 或发布平台客户端。</p>
 */
public interface OperationsTool {

    /**
     * 返回稳定且唯一的工具名，用于工具选择、调用日志、指标标签和最终报告。
     *
     * @return 工具的机器可读名称
     */
    String name();

    /**
     * 查询指定服务，并把结果转换成统一证据。
     *
     * @param serviceName 要调查的服务标准名
     * @return 永不为 null 的工具证据；查不到服务时返回 PARTIAL，而不是伪造正常数据
     */
    ToolEvidence execute(String serviceName);
}
