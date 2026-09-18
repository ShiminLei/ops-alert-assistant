package com.enterprise.opsassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 application.yml 中 {@code ops-assistant.ai} 配置绑定成强类型 Java 对象。
 *
 * <p>配置类只保存参数，不负责创建客户端或执行调用。这样 local、test、prod 环境可以使用同一套
 * Java 代码，只通过 YAML 和环境变量切换主备提供方、模型名称、温度、Token 上限与超时。</p>
 */
@ConfigurationProperties(prefix = "ops-assistant.ai")
public class OpsAssistantAiProperties {

    /** 默认先调用的模型提供方名称，必须对应 providers 中的键。 */
    private String primaryProvider = "mock-primary";

    /** 主模型异常后尝试的备用提供方名称。 */
    private String backupProvider = "mock-backup";

    /** Chat Memory 后续允许保留的最大历史消息数。 */
    private int maxHistoryMessages = 20;

    /** 按名称保存所有模型提供方配置，LinkedHashMap 保留 YAML 声明顺序便于排查。 */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public String getPrimaryProvider() {
        return primaryProvider;
    }

    public void setPrimaryProvider(String primaryProvider) {
        this.primaryProvider = primaryProvider;
    }

    public String getBackupProvider() {
        return backupProvider;
    }

    public void setBackupProvider(String backupProvider) {
        this.backupProvider = backupProvider;
    }

    public int getMaxHistoryMessages() {
        return maxHistoryMessages;
    }

    public void setMaxHistoryMessages(int maxHistoryMessages) {
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public Map<String, Provider> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, Provider> providers) {
        this.providers = providers;
    }

    /**
     * 单个模型提供方的调用参数。
     * API Key 可以为空，使本地项目在没有真实密钥时仍能使用 Mock 模型启动。
     */
    public static class Provider {

        private boolean enabled = true;
        private String baseUrl;
        private String apiKey;
        private String model;
        private double temperature = 0.2;
        private int maxTokens = 2000;
        private Duration timeout = Duration.ofSeconds(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }
}
