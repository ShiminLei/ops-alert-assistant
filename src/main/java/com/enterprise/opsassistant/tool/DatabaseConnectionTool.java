package com.enterprise.opsassistant.tool;

import com.enterprise.opsassistant.domain.ToolEvidence;
import com.enterprise.opsassistant.mock.MockOperationsDataStore;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 查询数据库连接池和查询延迟的运维工具。 */
@Component
public class DatabaseConnectionTool implements OperationsTool {

    private final MockOperationsDataStore dataStore;

    public DatabaseConnectionTool(MockOperationsDataStore dataStore) {
        this.dataStore = dataStore;
    }

    @Override
    public String name() {
        return "database-connection";
    }

    /**
     * 返回连接占用、容量、等待线程和查询耗时。连接数达到上限且存在等待线程时，
     * 通常可以解释接口超时和 SQLTransientConnectionException。
     */
    @Override
    public ToolEvidence execute(String serviceName) {
        long startedAt = System.nanoTime();
        return dataStore.findByServiceName(serviceName)
                .map(snapshot -> {
                    var database = snapshot.database();
                    Map<String, Object> data = Map.of(
                            "state", database.state(),
                            "activeConnections", database.activeConnections(),
                            "maxConnections", database.maxConnections(),
                            "waitingThreads", database.waitingThreads(),
                            "averageQueryMs", database.averageQueryMs()
                    );
                    return ToolEvidenceFactory.success(name(), snapshot.serviceName(),
                            "数据库状态 " + database.state() + "，连接占用 "
                                    + database.activeConnections() + "/" + database.maxConnections()
                                    + "，等待线程 " + database.waitingThreads(),
                            data, startedAt);
                })
                .orElseGet(() -> ToolEvidenceFactory.serviceNotFound(name(), serviceName, startedAt));
    }
}
