package com.enterprise.opsassistant.tool;

import com.enterprise.opsassistant.domain.ToolEvidence;
import com.enterprise.opsassistant.mock.MockOperationsDataStore;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 查询最近一次发布记录的运维工具，用于识别变更引发的回归故障。 */
@Component
public class DeploymentTool implements OperationsTool {

    private final MockOperationsDataStore dataStore;

    public DeploymentTool(MockOperationsDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public String name() {
        return "deployment";
    }

    /** 返回当前/上一版本、发布时间和变更摘要，为是否回滚提供依据。 */
    @Override
    public ToolEvidence execute(String serviceName) {
        long startedAt = System.nanoTime();
        return dataStore.findByServiceName(serviceName)
                .map(snapshot -> {
                    var deployment = snapshot.deployment();
                    Map<String, Object> data = Map.of(
                            "version", deployment.version(),
                            "previousVersion", deployment.previousVersion(),
                            "deployedAt", deployment.deployedAt().toString(),
                            "operator", deployment.operator(),
                            "changeSummary", deployment.changeSummary()
                    );
                    return ToolEvidenceFactory.success(name(), snapshot.serviceName(),
                            "最近版本 " + deployment.version() + " 发布于 " + deployment.deployedAt(),
                            data, startedAt);
                })
                .orElseGet(() -> ToolEvidenceFactory.serviceNotFound(name(), serviceName, startedAt));
    }
}
