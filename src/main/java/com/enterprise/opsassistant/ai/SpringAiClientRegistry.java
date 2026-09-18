package com.enterprise.opsassistant.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 保存所有启用的 Spring AI Provider 客户端。
 *
 * <p>注册表不决定谁是主模型或备用模型，那属于路由策略；它只负责按名称查找客户端并拒绝重名。
 * 这种拆分使模型创建、客户端索引和故障转移三个职责互不混杂。</p>
 */
public class SpringAiClientRegistry {

    private final Map<String, SpringAiProviderClient> clients;

    public SpringAiClientRegistry(List<SpringAiProviderClient> clients) {
        Map<String, SpringAiProviderClient> indexed = new LinkedHashMap<>();
        for (SpringAiProviderClient client : clients) {
            String key = normalize(client.providerName());
            if (indexed.putIfAbsent(key, client) != null) {
                throw new IllegalArgumentException("duplicate Spring AI provider: " + client.providerName());
            }
        }
        this.clients = Map.copyOf(indexed);
    }

    /** Provider 名称大小写不敏感，空名称表示没有匹配项。 */
    public Optional<SpringAiProviderClient> find(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(clients.get(normalize(providerName)));
    }

    /** 主要供启动检查和集成测试确认启用模型数量。 */
    public int size() {
        return clients.size();
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
