package com.enterprise.opsassistant.catalog;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 课程作业使用的只读内存服务目录。
 *
 * <p>目录除了 Mock 数据仓库直接保存的 payment-service 和 order-service，也登记事故场景中的
 * risk-engine 与 inventory-service。后两者可能暂时没有完整 Mock 快照，但它们仍是已知服务；
 * 工具返回 PARTIAL 表示“服务存在但当前数据源没有快照”，不同于模型凭空创造了服务。</p>
 */
@Component
public class InMemoryServiceCatalog implements ServiceCatalog {

    /** LinkedHashMap 保持稳定顺序，使 Prompt、测试和日志输出可重复。 */
    private final Map<String, ServiceDefinition> services = buildServices();

    @Override
    public List<ServiceDefinition> listServices() {
        return List.copyOf(services.values());
    }

    @Override
    public Optional<ServiceDefinition> findByCanonicalName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(services.get(serviceName.trim().toLowerCase(Locale.ROOT)));
    }

    @Override
    public Optional<ServiceDefinition> resolveFromText(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String normalized = text.toLowerCase(Locale.ROOT);

        // 标准名优先于别名，避免一个较短别名意外覆盖用户明确写出的服务标识。
        List<ServiceDefinition> canonicalMatches = services.values().stream()
                .filter(service -> normalized.contains(service.canonicalName()))
                .toList();
        if (canonicalMatches.size() == 1) {
            return Optional.of(canonicalMatches.get(0));
        }
        if (canonicalMatches.size() > 1) {
            return Optional.empty();
        }

        List<ServiceDefinition> aliasMatches = services.values().stream()
                .filter(service -> service.aliases().stream()
                        .map(alias -> alias.toLowerCase(Locale.ROOT))
                        .anyMatch(normalized::contains))
                .toList();
        return aliasMatches.size() == 1
                ? Optional.of(aliasMatches.get(0))
                : Optional.empty();
    }

    private Map<String, ServiceDefinition> buildServices() {
        Map<String, ServiceDefinition> result = new LinkedHashMap<>();
        register(result, "payment-service", "支付服务", "支付接口", "支付系统", "收单核心");
        register(result, "order-service", "订单服务", "订单系统", "订单中心");
        register(result, "risk-engine", "风控服务", "风控引擎", "风险引擎");
        register(result, "inventory-service", "库存服务", "库存系统", "库存中心");
        return Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private void register(Map<String, ServiceDefinition> target,
                          String canonicalName,
                          String... aliases) {
        ServiceDefinition definition = new ServiceDefinition(canonicalName, List.of(aliases));
        ServiceDefinition previous = target.putIfAbsent(definition.canonicalName(), definition);
        if (previous != null) {
            throw new IllegalStateException("duplicate service in catalog: " + canonicalName);
        }
    }
}
