package com.enterprise.opsassistant.report;

import com.enterprise.opsassistant.agent.AlertParserAgent;
import com.enterprise.opsassistant.agent.EvidenceCollectorAgent;
import com.enterprise.opsassistant.agent.ResponsePlanAgent;
import com.enterprise.opsassistant.agent.RootCauseAgent;
import com.enterprise.opsassistant.agent.SupervisorAgent;
import com.enterprise.opsassistant.agent.ToolPlanningAgent;
import com.enterprise.opsassistant.mock.MockOperationsDataStore;
import com.enterprise.opsassistant.tool.DatabaseConnectionTool;
import com.enterprise.opsassistant.tool.DeploymentTool;
import com.enterprise.opsassistant.tool.DependencyStatusTool;
import com.enterprise.opsassistant.tool.ErrorLogTool;
import com.enterprise.opsassistant.tool.OperationsTool;
import com.enterprise.opsassistant.tool.ResourceUsageTool;
import com.enterprise.opsassistant.tool.ServiceStatusTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 Markdown 生成器直接使用结构化报告，并完整保留证据关联。 */
class MarkdownReportGeneratorTest {

    /** 生成的人工报告应包含主要章节、证据 ID、置信度和观察清单。 */
    @Test
    void shouldGenerateCompleteMarkdownFromIncidentReport() {
        var dataStore = new MockOperationsDataStore();
        List<OperationsTool> tools = List.of(
                new ServiceStatusTool(dataStore),
                new ErrorLogTool(dataStore),
                new DeploymentTool(dataStore),
                new ResourceUsageTool(dataStore),
                new DependencyStatusTool(dataStore),
                new DatabaseConnectionTool(dataStore)
        );
        var supervisor = new SupervisorAgent(
                new AlertParserAgent(),
                new ToolPlanningAgent(),
                new EvidenceCollectorAgent(tools),
                new RootCauseAgent(),
                new ResponsePlanAgent()
        );
        var report = supervisor.analyze(
                "支付服务刚发布后大量请求超时，错误率18.7%，用户支付失败"
        );
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var generator = new MarkdownReportGenerator(objectMapper);

        String markdown = generator.generate(report);

        assertThat(markdown).contains(
                "## 1. 原始告警",
                "## 3. 异常指标",
                "## 4. 运维工具证据",
                "## 5. 根因与风险判断",
                "## 6. 建议处置动作",
                "## 7. 后续观察指标",
                "95%",
                report.evidence().get(0).evidenceId(),
                "- [ ] 服务错误率"
        );
    }
}
