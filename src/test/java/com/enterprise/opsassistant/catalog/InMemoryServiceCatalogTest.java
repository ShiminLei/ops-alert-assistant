package com.enterprise.opsassistant.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证内存服务目录是告警服务名的唯一可信来源。 */
class InMemoryServiceCatalogTest {

    private final InMemoryServiceCatalog catalog = new InMemoryServiceCatalog();

    /** 标准名和中文别名都应解析到同一项真实服务。 */
    @Test
    void shouldResolveCanonicalNamesAndAliases() {
        assertThat(catalog.resolveFromText("payment-service 出现大量超时"))
                .get()
                .extracting(ServiceCatalog.ServiceDefinition::canonicalName)
                .isEqualTo("payment-service");
        assertThat(catalog.resolveFromText("支付接口错误率升高"))
                .get()
                .extracting(ServiceCatalog.ServiceDefinition::canonicalName)
                .isEqualTo("payment-service");
        assertThat(catalog.resolveFromText("库存中心响应缓慢"))
                .get()
                .extracting(ServiceCatalog.ServiceDefinition::canonicalName)
                .isEqualTo("inventory-service");
    }

    /** 看起来像服务名但未登记的字符串不能通过目录验证。 */
    @Test
    void shouldRejectUnregisteredServiceNames() {
        assertThat(catalog.contains("payment-service")).isTrue();
        assertThat(catalog.contains("invented-service")).isFalse();
        assertThat(catalog.resolveFromText("invented-service 出现异常")).isEmpty();
        assertThat(catalog.resolveFromText("payment-service 调用 order-service 失败"))
                .as("同时出现多个服务时不能擅自选择其中一个")
                .isEmpty();
    }

    /** Prompt 上下文必须来自同一目录，并同时提供标准名和业务别名。 */
    @Test
    void shouldExposeCompactPromptContext() {
        assertThat(catalog.promptContext())
                .contains("payment-service", "支付服务", "order-service", "订单服务")
                .contains("risk-engine", "inventory-service");
    }
}
