# 企业运维告警智能处置助手

基于 Java 和 Spring Boot 的企业运维告警分析项目。项目将逐步实现告警识别、Mock 运维工具、多 Agent 分析、主备模型、流式返回、连续追问、可观测性和 Markdown 报告。

## 当前阶段

已完成自然语言告警识别、六个 Mock 运维工具、多 Agent 编排、Spring AI 主备模型、Tool Calling、
Structured Output、Chat Memory、Resilience4j、Prometheus 指标、Markdown 报告及 SSE 流式页面。

## SSE 事件协议

`POST /api/v1/alerts/analyze/stream` 返回 `text/event-stream`。每个 SSE `data` 都使用统一信封：

```json
{
  "id": 5,
  "runId": "analysis-id",
  "seq": 4,
  "type": "ai-token",
  "data": {},
  "occurredAt": "2026-09-19T00:00:00Z"
}
```

- `id`：整条 SSE 连接内从 1 开始递增，同时写入协议层 `id:` 字段。
- `runId`：业务任务编号，本项目中等于 `analysisId`。
- `seq`：同一个 Run 内所有事件共用的顺序号，从 0 开始。
- `type`：与协议层 `event:` 一致，当前包括 `progress`、`ai-token`、`report` 和 `error`。
- `data`：具体业务载荷。AI 载荷中的 `chunkSequence` 仅表示当前模型尝试内的文本片段顺序，
  重试或切换备用模型时会归零，不等同于 Run 级 `seq`。

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
