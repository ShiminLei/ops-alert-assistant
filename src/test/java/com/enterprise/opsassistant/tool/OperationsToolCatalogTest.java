package com.enterprise.opsassistant.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 AI 可见工具目录与 Spring 实际工具注册保持一致。 */
class OperationsToolCatalogTest {

    @Test
    void shouldExposeOnlyActuallyRegisteredTools() {
        OperationsToolCatalog catalog = new OperationsToolCatalog(List.of(
                tool("service-status"), tool("error-log"), tool("resource-usage")));

        assertThat(catalog.listTools())
                .extracting(OperationsToolCatalog.ToolDefinition::name)
                .containsExactly("service-status", "error-log", "resource-usage");
        assertThat(catalog.contains("service-status")).isTrue();
        assertThat(catalog.contains("delete-production")).isFalse();
        assertThat(catalog.promptContext()).contains("服务健康状态", "近期错误", "CPU");
    }

    /** 新工具如果没有维护能力说明，应在启动时暴露问题而不是静默出现在执行层。 */
    @Test
    void shouldRejectRegisteredToolWithoutDescription() {
        assertThatThrownBy(() -> new OperationsToolCatalog(List.of(tool("unknown-tool"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing tool description");
    }

    private OperationsTool tool(String name) {
        OperationsTool tool = mock(OperationsTool.class);
        when(tool.name()).thenReturn(name);
        return tool;
    }
}
