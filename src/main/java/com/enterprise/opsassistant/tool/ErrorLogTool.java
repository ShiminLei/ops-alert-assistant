package com.enterprise.opsassistant.tool;

import com.enterprise.opsassistant.domain.ToolEvidence;
import com.enterprise.opsassistant.mock.MockOperationsDataStore;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 查询服务最近关键错误日志的运维工具。 */
@Component
public class ErrorLogTool implements OperationsTool {

    private final MockOperationsDataStore dataStore;

    public ErrorLogTool(MockOperationsDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public String name() {
        return "error-log";
    }

    /**
     * 返回保留原文的近期日志。日志原文是重要证据，后续模型可以从异常类型和错误码中提取线索。
     */
    @Override
    public ToolEvidence execute(String serviceName) {
        long startedAt = System.nanoTime();
        return dataStore.findByServiceName(serviceName)
                .map(snapshot -> ToolEvidenceFactory.success(
                        name(),
                        snapshot.serviceName(),
                        "查询到 " + snapshot.recentLogs().size() + " 条近期关键日志",
                        Map.of("logs", snapshot.recentLogs(), "capturedAt", snapshot.capturedAt().toString()),
                        startedAt
                ))
                .orElseGet(() -> ToolEvidenceFactory.serviceNotFound(name(), serviceName, startedAt));
    }
}
