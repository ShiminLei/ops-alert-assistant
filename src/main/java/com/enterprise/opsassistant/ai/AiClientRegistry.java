package com.enterprise.opsassistant.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 按提供方名称保存所有已启用 AI 客户端的只读仓库。
 *
 * <p>独立仓库比在路由器中直接接收松散 List 更容易校验重名，并让配置创建与主备策略各自保持
 * 单一职责。</p>
 */
public class AiClientRegistry {

    private final Map<String, AiChatClient> clients;

    /** 将名称统一成小写并拒绝重复 Provider，避免路由结果依赖 Bean 注册顺序。 */
    public AiClientRegistry(List<AiChatClient> clients) {
        Map<String, AiChatClient> indexed = new LinkedHashMap<>();
        for (AiChatClient client : clients) {
            String key = normalize(client.providerName());
            if (indexed.putIfAbsent(key, client) != null) {
                throw new IllegalArgumentException("duplicate AI provider: " + client.providerName());
            }
        }
        this.clients = Map.copyOf(indexed);
    }

    /** @return 指定提供方客户端；名称大小写不敏感 */
    public Optional<AiChatClient> find(String providerName) {
        if (providerName == null || providerName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(clients.get(normalize(providerName)));
    }

    /** @return 已启用提供方数量，主要用于健康检查和测试 */
    public int size() {
        return clients.size();
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
