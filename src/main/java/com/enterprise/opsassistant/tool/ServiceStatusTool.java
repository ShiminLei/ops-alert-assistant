package com.enterprise.opsassistant.tool;

import com.enterprise.opsassistant.domain.ToolEvidence;
import com.enterprise.opsassistant.mock.MockOperationsDataStore;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 查询服务实例健康状态和整体可用率的运维工具。 */
@Component
public class ServiceStatusTool implements OperationsTool {

    private final MockOperationsDataStore dataStore;

    /** 通过构造器注入数据源，便于测试并避免隐藏依赖。 */
    public ServiceStatusTool(MockOperationsDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public String name() {
        return "service-status";
    }

    /** 返回健康实例数、总实例数和可用率，帮助判断故障影响范围。 */
    @Override
    public ToolEvidence execute(String serviceName) {
        long startedAt = System.nanoTime();
        return dataStore.findByServiceName(serviceName)
                .map(snapshot -> {
                    var health = snapshot.health();
                    Map<String, Object> data = Map.of(
                            "state", health.state(),
                            "healthyInstances", health.healthyInstances(),
                            "totalInstances", health.totalInstances(),
                            "availabilityPercent", health.availabilityPercent(),
                            "capturedAt", snapshot.capturedAt().toString()
                    );
                    return ToolEvidenceFactory.success(name(), snapshot.serviceName(),
                            "服务状态为 " + health.state() + "，健康实例 "
                                    + health.healthyInstances() + "/" + health.totalInstances(),
                            data, startedAt);
                })
                .orElseGet(() -> ToolEvidenceFactory.serviceNotFound(name(), serviceName, startedAt));
    }
}
