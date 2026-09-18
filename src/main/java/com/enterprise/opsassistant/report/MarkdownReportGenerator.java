package com.enterprise.opsassistant.report;

import com.enterprise.opsassistant.domain.IncidentReport;
import com.enterprise.opsassistant.domain.MetricObservation;
import com.enterprise.opsassistant.domain.RecommendedAction;
import com.enterprise.opsassistant.domain.RootCauseCandidate;
import com.enterprise.opsassistant.domain.ToolEvidence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 将结构化 {@link IncidentReport} 转换成完整的 Markdown 运维报告。
 *
 * <p>JSON 适合前端和系统集成，Markdown 更适合人阅读、提交作业、粘贴到知识库以及事故复盘。
 * 生成器只负责格式转换，不重新分析告警，因此 Markdown 与 REST JSON 始终来自同一份事实数据。</p>
 */
@Service
public class MarkdownReportGenerator {

    private final ObjectMapper objectMapper;

    /** 注入 Spring 已配置好的 ObjectMapper，保证时间和 JSON 字段格式与 REST API 一致。 */
    public MarkdownReportGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 生成可直接保存为 .md 文件的报告。
     *
     * @param report SupervisorAgent 生成的最终结构化报告
     * @return UTF-8 Markdown 文本
     */
    public String generate(IncidentReport report) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null");
        }

        StringBuilder markdown = new StringBuilder();
        appendTitleAndMetadata(markdown, report);
        appendOriginalAlert(markdown, report);
        appendRecognition(markdown, report);
        appendMetrics(markdown, report.recognition().abnormalMetrics());
        appendEvidence(markdown, report.evidence());
        appendRootCause(markdown, report);
        appendActions(markdown, report.recommendedActions());
        appendFollowUpMetrics(markdown, report.followUpMetrics());
        appendFooter(markdown);
        return markdown.toString();
    }

    /** 生成报告标题、关联编号和模型来源等审计元数据。 */
    private void appendTitleAndMetadata(StringBuilder markdown, IncidentReport report) {
        markdown.append("# 企业运维告警智能处置报告\n\n")
                .append("| 项目 | 内容 |\n")
                .append("|---|---|\n")
                .append("| 分析编号 | `").append(inlineCode(report.analysisId())).append("` |\n")
                .append("| 会话编号 | `").append(inlineCode(report.conversationId())).append("` |\n")
                .append("| 生成时间 | ").append(tableText(report.generatedAt().toString())).append(" |\n")
                .append("| 服务 | `").append(inlineCode(report.recognition().serviceName())).append("` |\n")
                .append("| 告警类型 | `").append(report.recognition().alertType()).append("` |\n")
                .append("| 最终风险 | **").append(report.rootCause().finalRisk()).append("** |\n")
                .append("| 分析提供方 | `").append(inlineCode(report.modelProvider())).append("` |\n")
                .append("| 分析模型 | `").append(inlineCode(report.modelName())).append("` |\n")
                .append("| 是否使用备用模型 | ").append(yesNo(report.fallbackUsed())).append(" |\n\n");
    }

    /** 使用引用块保留用户原始告警，便于审计结构化结果是否忠于输入。 */
    private void appendOriginalAlert(StringBuilder markdown, IncidentReport report) {
        markdown.append("## 1. 原始告警\n\n");
        String[] lines = report.originalAlert().replace("\r", "").split("\n", -1);
        for (String line : lines) {
            markdown.append("> ").append(line).append("\n");
        }
        markdown.append("\n");
    }

    /** 输出告警识别阶段的归一化结论。 */
    private void appendRecognition(StringBuilder markdown, IncidentReport report) {
        var recognition = report.recognition();
        markdown.append("## 2. 告警识别\n\n")
                .append("- 服务：`").append(inlineCode(recognition.serviceName())).append("`\n")
                .append("- 类型：`").append(recognition.alertType()).append("`\n")
                .append("- 初始风险：**").append(recognition.initialRisk()).append("**\n")
                .append("- 已确认用户影响：").append(yesNo(recognition.userImpact())).append("\n")
                .append("- 识别阶段建议升级：").append(yesNo(recognition.escalationSuggested())).append("\n")
                .append("- 摘要：").append(plainText(recognition.summary())).append("\n\n");
    }

    /** 将从原始告警中提取的异常指标输出为统一表格。 */
    private void appendMetrics(StringBuilder markdown, List<MetricObservation> metrics) {
        markdown.append("## 3. 异常指标\n\n");
        if (metrics.isEmpty()) {
            markdown.append("原始告警中没有可直接提取的数值指标，需以工具证据为准。\n\n");
            return;
        }

        markdown.append("| 指标 | 当前值 | 阈值 | 趋势 | 采集时间 |\n")
                .append("|---|---:|---:|---|---|\n");
        for (MetricObservation metric : metrics) {
            String threshold = metric.threshold() == null
                    ? "-"
                    : formatNumber(metric.threshold()) + metric.unit();
            markdown.append("| ").append(tableText(metric.metricName()))
                    .append(" | ").append(formatNumber(metric.currentValue())).append(tableText(metric.unit()))
                    .append(" | ").append(tableText(threshold))
                    .append(" | ").append(metric.trend())
                    .append(" | ").append(metric.observedAt())
                    .append(" |\n");
        }
        markdown.append("\n");
    }

    /**
     * 输出所有工具证据的摘要和结构化数据。
     * evidenceId 同时出现在根因候选中，读者可以据此回查结论依据。
     */
    private void appendEvidence(StringBuilder markdown, List<ToolEvidence> evidence) {
        markdown.append("## 4. 运维工具证据\n\n")
                .append("| 证据编号 | 工具 | 状态 | 摘要 | 耗时 |\n")
                .append("|---|---|---|---|---:|\n");
        for (ToolEvidence item : evidence) {
            markdown.append("| `").append(inlineCode(item.evidenceId())).append("` | `")
                    .append(inlineCode(item.toolName())).append("` | ")
                    .append(item.status()).append(" | ")
                    .append(tableText(item.summary())).append(" | ")
                    .append(item.durationMs()).append(" ms |\n");
        }
        markdown.append("\n### 证据数据明细\n\n");
        for (ToolEvidence item : evidence) {
            markdown.append("#### ").append(plainText(item.toolName()))
                    .append(" (`").append(inlineCode(item.evidenceId())).append("`)\n\n")
                    .append("```json\n")
                    .append(toPrettyJson(item))
                    .append("\n```\n\n");
        }
    }

    /** 输出根因候选、置信度、推理依据以及回滚/升级等关键决策。 */
    private void appendRootCause(StringBuilder markdown, IncidentReport report) {
        var assessment = report.rootCause();
        markdown.append("## 5. 根因与风险判断\n\n")
                .append("| 候选根因 | 置信度 | 支撑证据 |\n")
                .append("|---|---:|---|\n");
        for (RootCauseCandidate candidate : assessment.candidates()) {
            markdown.append("| ").append(tableText(candidate.description()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.0f%%", candidate.confidence() * 100))
                    .append(" | ").append(joinEvidenceIds(candidate.evidenceIds()))
                    .append(" |\n");
        }

        markdown.append("\n### 推理依据\n\n");
        for (int index = 0; index < assessment.reasoning().size(); index++) {
            markdown.append(index + 1).append(". ")
                    .append(plainText(assessment.reasoning().get(index))).append("\n");
        }

        markdown.append("\n### 安全决策\n\n")
                .append("- 最终风险：**").append(assessment.finalRisk()).append("**\n")
                .append("- 建议回滚：").append(yesNo(assessment.rollbackRecommended())).append("\n")
                .append("- 必须升级人工：").append(yesNo(assessment.escalationRequired())).append("\n")
                .append("- 用户影响：").append(plainText(assessment.userImpact())).append("\n\n");
    }

    /** 按经过领域模型校验的顺序输出处置动作。 */
    private void appendActions(StringBuilder markdown, List<RecommendedAction> actions) {
        markdown.append("## 6. 建议处置动作\n\n")
                .append("| 顺序 | 紧急程度 | 责任角色 | 操作 |\n")
                .append("|---:|---|---|---|\n");
        for (RecommendedAction action : actions) {
            markdown.append("| ").append(action.order())
                    .append(" | ").append(action.urgency())
                    .append(" | ").append(tableText(action.owner()))
                    .append(" | ").append(tableText(action.action()))
                    .append(" |\n");
        }
        markdown.append("\n");
    }

    /** 使用待办清单形式输出恢复验证指标，方便处置人员逐项确认。 */
    private void appendFollowUpMetrics(StringBuilder markdown, List<String> metrics) {
        markdown.append("## 7. 后续观察指标\n\n");
        for (String metric : metrics) {
            markdown.append("- [ ] ").append(plainText(metric)).append("\n");
        }
        markdown.append("\n");
    }

    /** 在报告末尾明确权限边界，防止建议被误解为已经自动执行。 */
    private void appendFooter(StringBuilder markdown) {
        markdown.append("---\n\n")
                .append("> 本报告由运维告警智能处置助手生成。生产回滚、扩容、限流和配置修改均需由有权限的人员确认并执行。\n");
    }

    /** 将完整 ToolEvidence 序列化成易读 JSON；极端序列化失败时仍保留基础文本数据。 */
    private String toPrettyJson(ToolEvidence evidence) {
        try {
            return safeCodeBlock(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        } catch (JsonProcessingException exception) {
            return safeCodeBlock(evidence.toString());
        }
    }

    /** 把证据编号显示为一组行内代码，便于与上文证据表对应。 */
    private String joinEvidenceIds(List<String> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return "-";
        }
        return evidenceIds.stream()
                .map(id -> "`" + inlineCode(id) + "`")
                .reduce((left, right) -> left + "、" + right)
                .orElse("-");
    }

    /** Markdown 表格中的竖线和换行必须转义，否则会破坏列结构。 */
    private String tableText(String value) {
        return plainText(value).replace("|", "\\|");
    }

    /** 普通文本将换行压缩为空格，避免用户输入改变报告结构。 */
    private String plainText(String value) {
        return value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
    }

    /** 行内代码中的反引号使用普通引号替代，避免提前关闭 Markdown 代码范围。 */
    private String inlineCode(String value) {
        return plainText(value).replace("`", "'");
    }

    /** 防止证据文本中出现三反引号并提前关闭 JSON 代码块。 */
    private String safeCodeBlock(String value) {
        return value.replace("```", "` ` `");
    }

    /** 使用中文“是/否”展示布尔决策，减少人工报告的理解成本。 */
    private String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    /** 整数不显示无意义小数位，其他数字最多保留两位小数。 */
    private String formatNumber(double value) {
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
