package com.enterprise.opsassistant.ai;

/** OpenAI 兼容聊天协议支持的消息角色。 */
public enum AiRole {
    /** 定义模型身份、任务边界和安全规则。 */
    SYSTEM("system"),
    /** 用户输入或由系统代表用户构造的分析材料。 */
    USER("user"),
    /** 历史对话中模型曾经给出的回答。 */
    ASSISTANT("assistant");

    private final String apiValue;

    AiRole(String apiValue) {
        this.apiValue = apiValue;
    }

    /** @return 发送给 OpenAI 兼容接口的角色字符串 */
    public String apiValue() {
        return apiValue;
    }
}
