package com.enterprise.opsassistant.catalog;

import java.util.List;
import java.util.Optional;

/**
 * 企业服务目录抽象，是告警文本和真实系统服务之间的可信边界。
 *
 * <p>模型本身不知道企业内部有哪些服务，也不能因为一个字符串看起来像服务名就认定它存在。
 * 告警理解阶段只能从本目录列出的标准名和别名中选择；未来接入 CMDB、注册中心或服务治理平台时，
 * 可以替换本接口的实现，而无需修改 AlertParserAgent 和后续工具调用链。</p>
 */
public interface ServiceCatalog {

    /** 返回当前允许告警分析引用的全部服务定义。 */
    List<ServiceDefinition> listServices();

    /** 根据标准服务名查询；比较应忽略用户输入两端空白和大小写。 */
    Optional<ServiceDefinition> findByCanonicalName(String serviceName);

    /** 从自然语言中匹配标准名或别名；无法唯一识别时返回空结果。 */
    Optional<ServiceDefinition> resolveFromText(String text);

    /** 判断模型返回的服务标准名是否真实存在于当前目录。 */
    default boolean contains(String serviceName) {
        return findByCanonicalName(serviceName).isPresent();
    }

    /**
     * 提供给模型的精简目录文本。
     *
     * <p>当前作业只有少量服务，可以直接放入 Prompt；真实企业服务规模较大时，实现类应改为
     * 基于关键词检索少量候选，或暴露 service-catalog Tool，而不是把整个 CMDB 塞入上下文。</p>
     */
    default String promptContext() {
        return listServices().stream()
                .map(service -> "- " + service.canonicalName()
                        + "（别名：" + String.join("、", service.aliases()) + "）")
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- 当前没有已注册服务");
    }

    /**
     * 一项服务目录记录。
     *
     * @param canonicalName 工具、日志和报告统一使用的标准服务名
     * @param aliases 用户告警中常见的中文名、业务名或简称
     */
    record ServiceDefinition(String canonicalName, List<String> aliases) {

        /** 防止无效目录数据进入共享识别链路，并将别名保存为不可变快照。 */
        public ServiceDefinition {
            if (canonicalName == null || canonicalName.isBlank()) {
                throw new IllegalArgumentException("canonicalName must not be blank");
            }
            canonicalName = canonicalName.trim().toLowerCase();
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
        }
    }
}
