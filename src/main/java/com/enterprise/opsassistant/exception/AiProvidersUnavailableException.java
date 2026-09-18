package com.enterprise.opsassistant.exception;

/**
 * 主模型与备用模型均不可用时抛出的聚合异常。
 *
 * <p>异常不保存 API Key 或完整请求，只保存提供方名称。原始主模型异常作为 cause，备用模型异常
 * 作为 suppressed exception，服务端仍可看到两次失败原因。</p>
 */
public class AiProvidersUnavailableException extends RuntimeException {

    private final String primaryProvider;
    private final String backupProvider;

    public AiProvidersUnavailableException(String primaryProvider,
                                           String backupProvider,
                                           Throwable primaryFailure,
                                           Throwable backupFailure) {
        super("primary and backup AI providers are unavailable", primaryFailure);
        this.primaryProvider = primaryProvider;
        this.backupProvider = backupProvider;
        if (backupFailure != null) {
            addSuppressed(backupFailure);
        }
    }

    public String getPrimaryProvider() {
        return primaryProvider;
    }

    public String getBackupProvider() {
        return backupProvider;
    }
}
