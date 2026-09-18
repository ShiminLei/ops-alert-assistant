package com.enterprise.opsassistant.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 当前 Spring 容器中真实注册的只读运维工具目录。
 *
 * <p>模型不能凭提示词中的示例创造工具名。目录在启动时读取所有 {@link OperationsTool}
 * Bean，并将其稳定名称和用途提供给 AI；ToolPlanningAgent 随后还会用同一个目录校验模型结果。
 * 因此“模型可见的工具”和“Java 实际可执行的工具”来自同一事实来源。</p>
 */
@Component
public class OperationsToolCatalog {

    private static final Map<String, String> DESCRIPTIONS = Map.of(
            "service-status", "查询服务健康状态、实例数量和整体可用率",
            "error-log", "查询近期错误、警告和关键请求日志",
            "resource-usage", "查询 CPU、内存、错误率、请求量和 P99 延迟",
            "deployment", "查询最近发布版本、上一版本、发布时间和变更摘要",
            "dependency-status", "查询下游依赖的健康状态和调用延迟",
            "database-connection", "查询数据库连接池、等待线程和查询耗时"
    );

    private final Map<String, ToolDefinition> tools;

    /**
     * 根据实际注入的工具 Bean 建立目录。
     * 未维护描述或名称重复时直接阻止应用启动，避免 AI 看到不完整或歧义的工具清单。
     */
    public OperationsToolCatalog(List<OperationsTool> registeredTools) {
        Map<String, ToolDefinition> indexed = new LinkedHashMap<>();
        for (OperationsTool tool : registeredTools) {
            String name = tool.name();
            String description = DESCRIPTIONS.get(name);
            if (description == null) {
                throw new IllegalStateException("missing tool description: " + name);
            }
            ToolDefinition previous = indexed.putIfAbsent(
                    name, new ToolDefinition(name, description));
            if (previous != null) {
                throw new IllegalStateException("duplicate operations tool: " + name);
            }
        }
        this.tools = java.util.Collections.unmodifiableMap(indexed);
    }

    /** 返回真实注册工具的稳定快照。 */
    public List<ToolDefinition> listTools() {
        return List.copyOf(tools.values());
    }

    /** 按精确机器名查询工具。 */
    public Optional<ToolDefinition> find(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get(toolName.trim()));
    }

    /** 判断 AI 提出的工具是否真的注册在当前 Spring 容器中。 */
    public boolean contains(String toolName) {
        return find(toolName).isPresent();
    }

    /** 生成精简 Prompt 上下文，模型只能从这些稳定名称中选择。 */
    public String promptContext() {
        return listTools().stream()
                .map(tool -> "- " + tool.name() + "：" + tool.description())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- 当前没有已注册工具");
    }

    /** @param name 工具机器名 @param description 提供给模型的只读能力说明 */
    public record ToolDefinition(String name, String description) {
    }
}
