# 企业运维告警智能处置助手

基于 Java 和 Spring Boot 的企业运维告警分析项目。项目将逐步实现告警识别、Mock 运维工具、多 Agent 分析、主备模型、流式返回、连续追问、可观测性和 Markdown 报告。

## 当前阶段

已完成 Spring Boot Maven 工程骨架和 `local`、`test`、`prod` 多环境配置，业务功能将在后续步骤中逐步实现。

## 环境要求

- JDK 17 或更高版本
- Maven 3.9 或使用项目 Maven Wrapper

## 本地启动

```bash
./mvnw spring-boot:run
```

默认使用 `local` 环境和 Mock AI Provider，启动后可访问：

- Health: http://localhost:8080/actuator/health
- OpenAPI: http://localhost:8080/swagger-ui/index.html
- Prometheus: http://localhost:8080/actuator/prometheus
