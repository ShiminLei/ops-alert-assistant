package com.enterprise.opsassistant.mock;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 集中保存课程作业使用的 Mock 运维数据。
 *
 * <p>真实企业通常会分别从监控平台、日志平台、发布平台、服务治理平台和数据库管理平台取数。
 * 本项目暂时不连接这些外部系统，而是在一个内存仓库中构造一致的事故现场。六个运维工具查询
 * 同一份 {@link ServiceSnapshot}，可以避免出现“日志说刚发布，但发布工具却查不到发布记录”之类
 * 相互矛盾的演示数据。</p>
 *
 * <p>{@link Repository} 表示该类负责数据访问。当前实现是只读内存 Map，将来接真实平台时，
 * 工具接口和上层 Agent 无需跟着改变，只需替换这个数据访问实现。</p>
 */
@Repository
public class MockOperationsDataStore {

    /**
     * 按服务名索引的不可变事故快照。
     * payment-service 用于演示故障场景，order-service 用于演示健康对照场景。
     */
    private final Map<String, ServiceSnapshot> snapshots = Map.of(
            "payment-service", paymentIncident(),
            "order-service", healthyOrderService()
    );

    /**
     * 根据标准服务名查找快照。
     *
     * @param serviceName 告警识别阶段得到的服务名
     * @return 找到时返回快照；未知服务返回空 Optional，由工具转换为 PARTIAL 证据
     */
    public Optional<ServiceSnapshot> findByServiceName(String serviceName) {
        if (serviceName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshots.get(serviceName.trim().toLowerCase()));
    }

    /**
     * 构造“支付服务发布后数据库连接池耗尽”的完整事故现场。
     * 各项数据故意互相印证，后续 RootCauseAgent 才能展示基于证据的推理过程。
     */
    private static ServiceSnapshot paymentIncident() {
        return new ServiceSnapshot(
                "payment-service",
                Instant.parse("2026-09-18T08:00:00Z"),
                new ServiceHealth("DEGRADED", 2, 4, 62.5),
                List.of(
                        "ERROR HikariPool-1 - Connection is not available, request timed out after 3000ms",
                        "ERROR PaymentController - payment request failed: SQLTransientConnectionException",
                        "WARN  CheckoutClient - /api/pay latency=2480ms status=504",
                        "INFO  TrafficSummary - requests=920/min errorRate=18.7%"
                ),
                new DeploymentSnapshot(
                        "2.4.1",
                        "2.4.0",
                        Instant.parse("2026-09-18T07:52:00Z"),
                        "release-pipeline",
                        "数据库连接池 maximum-pool-size 从 100 调整为 20"
                ),
                new ResourceSnapshot(86.4, 72.1, 2450, 18.7, 920),
                List.of(
                        new DependencySnapshot("risk-engine", "DEGRADED", 1850),
                        new DependencySnapshot("inventory-service", "HEALTHY", 55)
                ),
                new DatabaseSnapshot("SATURATED", 20, 20, 64, 1480)
        );
    }

    /** 构造健康服务快照，便于验证工具不会把所有输入都判断成故障。 */
    private static ServiceSnapshot healthyOrderService() {
        return new ServiceSnapshot(
                "order-service",
                Instant.parse("2026-09-18T08:00:00Z"),
                new ServiceHealth("HEALTHY", 6, 6, 99.99),
                List.of("INFO OrderController - request completed status=200 latency=42ms"),
                new DeploymentSnapshot(
                        "5.8.0",
                        "5.7.3",
                        Instant.parse("2026-09-15T03:00:00Z"),
                        "release-pipeline",
                        "常规功能发布"
                ),
                new ResourceSnapshot(34.2, 48.5, 82, 0.08, 450),
                List.of(new DependencySnapshot("inventory-service", "HEALTHY", 48)),
                new DatabaseSnapshot("HEALTHY", 18, 100, 0, 12)
        );
    }

    /**
     * 一个服务在同一采集时刻的完整运维快照。
     *
     * @param serviceName 服务标准名
     * @param capturedAt 快照采集时间
     * @param health 实例健康和可用性数据
     * @param recentLogs 最近的关键日志
     * @param deployment 最近一次发布信息
     * @param resources 资源和请求指标
     * @param dependencies 下游依赖状态
     * @param database 数据库连接状态
     */
    public record ServiceSnapshot(
            String serviceName,
            Instant capturedAt,
            ServiceHealth health,
            List<String> recentLogs,
            DeploymentSnapshot deployment,
            ResourceSnapshot resources,
            List<DependencySnapshot> dependencies,
            DatabaseSnapshot database) {

        /** 对列表进行防御性复制，使模拟事故现场在应用运行期间保持不变。 */
        public ServiceSnapshot {
            recentLogs = List.copyOf(recentLogs);
            dependencies = List.copyOf(dependencies);
        }
    }

    /** @param state 服务总体状态 @param healthyInstances 健康实例数 @param totalInstances 总实例数 @param availabilityPercent 可用率 */
    public record ServiceHealth(String state, int healthyInstances, int totalInstances, double availabilityPercent) {
    }

    /** @param version 当前版本 @param previousVersion 上一版本 @param deployedAt 发布时间 @param operator 发布者 @param changeSummary 变更摘要 */
    public record DeploymentSnapshot(String version, String previousVersion, Instant deployedAt,
                                     String operator, String changeSummary) {
    }

    /** @param cpuPercent CPU 使用率 @param memoryPercent 内存使用率 @param p99LatencyMs P99 延迟 @param errorRatePercent 错误率 @param requestsPerMinute 每分钟请求数 */
    public record ResourceSnapshot(double cpuPercent, double memoryPercent, long p99LatencyMs,
                                   double errorRatePercent, long requestsPerMinute) {
    }

    /** @param name 依赖服务名 @param state 依赖状态 @param latencyMs 调用延迟 */
    public record DependencySnapshot(String name, String state, long latencyMs) {
    }

    /** @param state 数据库状态 @param activeConnections 活跃连接数 @param maxConnections 最大连接数 @param waitingThreads 等待连接的线程数 @param averageQueryMs 平均查询耗时 */
    public record DatabaseSnapshot(String state, int activeConnections, int maxConnections,
                                   int waitingThreads, long averageQueryMs) {
    }
}
