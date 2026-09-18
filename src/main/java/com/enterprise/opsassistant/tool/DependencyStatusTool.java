package com.enterprise.opsassistant.tool;

import com.enterprise.opsassistant.domain.ToolEvidence;
import com.enterprise.opsassistant.mock.MockOperationsDataStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 查询下游服务和中间件依赖状态的运维工具。 */
@Component
public class DependencyStatusTool implements OperationsTool {

    private final MockOperationsDataStore dataStore;

    public DependencyStatusTool(MockOperationsDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public String name() {
        return "dependency-status";
    }

    /** 把强类型依赖快照转换成适合 JSON、模型提示词和报告展示的结构化列表。 */
    @Override
    public ToolEvidence execute(String serviceName) {
        long startedAt = System.nanoTime();
        return dataStore.findByServiceName(serviceName)
                .map(snapshot -> {
                    List<Map<String, Object>> dependencies = snapshot.dependencies().stream()
                            .map(item -> Map.<String, Object>of(
                                    "name", item.name(),
                                    "state", item.state(),
                                    "latencyMs", item.latencyMs()))
                            .toList();
                    long abnormalCount = snapshot.dependencies().stream()
                            .filter(item -> !"HEALTHY".equals(item.state()))
                            .count();
                    return ToolEvidenceFactory.success(name(), snapshot.serviceName(),
                            "共检查 " + dependencies.size() + " 个依赖，其中异常 " + abnormalCount + " 个",
                            Map.of("dependencies", dependencies, "abnormalCount", abnormalCount),
                            startedAt);
                })
                .orElseGet(() -> ToolEvidenceFactory.serviceNotFound(name(), serviceName, startedAt));
    }
}
