package com.enterprise.opsassistant.tool;

import com.enterprise.opsassistant.domain.ToolEvidence;
import com.enterprise.opsassistant.mock.MockOperationsDataStore;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 查询 CPU、内存、延迟、错误率和流量的综合资源工具。 */
@Component
public class ResourceUsageTool implements OperationsTool {

    private final MockOperationsDataStore dataStore;

    public ResourceUsageTool(MockOperationsDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public String name() {
        return "resource-usage";
    }

    /** 返回同一采集窗口中的核心指标，避免比较来自不同时间点的数据。 */
    @Override
    public ToolEvidence execute(String serviceName) {
        long startedAt = System.nanoTime();
        return dataStore.findByServiceName(serviceName)
                .map(snapshot -> {
                    var resources = snapshot.resources();
                    Map<String, Object> data = Map.of(
                            "cpuPercent", resources.cpuPercent(),
                            "memoryPercent", resources.memoryPercent(),
                            "p99LatencyMs", resources.p99LatencyMs(),
                            "errorRatePercent", resources.errorRatePercent(),
                            "requestsPerMinute", resources.requestsPerMinute(),
                            "capturedAt", snapshot.capturedAt().toString()
                    );
                    return ToolEvidenceFactory.success(name(), snapshot.serviceName(),
                            "CPU " + resources.cpuPercent() + "%，错误率 "
                                    + resources.errorRatePercent() + "%，P99 "
                                    + resources.p99LatencyMs() + "ms",
                            data, startedAt);
                })
                .orElseGet(() -> ToolEvidenceFactory.serviceNotFound(name(), serviceName, startedAt));
    }
}
