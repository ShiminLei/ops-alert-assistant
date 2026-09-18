# 企业运维告警智能处置助手

这是一个基于 Java 17、Spring Boot 3.5.15 和 Spring AI 1.1.8 的企业运维告警分析项目。
用户输入自然语言告警后，系统会理解告警、规划只读调查工具、收集证据、综合根因、生成处置建议，
最后返回可追溯的结构化报告或 Markdown 报告。

项目采用“AI 负责语义推理，Java 负责安全边界”的设计。模型不能直接执行回滚、扩容、限流、
重启、数据库修改或其他生产写操作。

## 完整分析链路

```text
自然语言告警
  ↓
AlertParserAgent
  ├─ Java 建立告警事实和风险基线
  └─ Spring AI Structured Output 理解服务、告警类型和指标
  ↓
ToolPlanningAgent
  ├─ Spring AI 根据告警规划调查工具
  └─ Java 校验动态工具白名单并补齐基础工具
  ↓
EvidenceCollectorAgent
  └─ Java 执行至少 3 个只读 Mock 运维工具，每次调用生成 evidenceId
  ↓
RootCauseAgent
  ├─ Java 规则形成确定性根因和风险基线
  ├─ Spring AI 交叉分析真实工具证据
  └─ Java 校验模型引用的 evidenceId 和置信度
  ↓
ResponsePlanAgent
  ├─ Java 生成不可缺失的升级、止损、修复和观察动作
  ├─ Spring AI 补充现场处置建议
  └─ Java 校验动作类型、证据、权限和人工审批要求
  ↓
OpsAnalysisAiService
  └─ 使用 Spring AI 流式复核最终报告
  ↓
IncidentReport / SSE / Markdown
```

同一次分析会生成唯一 `analysisId`，并作为日志 `traceId`。同一轮所有 AI 阶段使用相同
`conversationId`，用于 Chat Memory 和连续追问。

## 多 Agent 分工

| Agent | 主要职责 | Spring AI 的作用 | Java 的安全职责 |
|---|---|---|---|
| `AlertParserAgent` | 理解原始告警 | 结构化识别服务、类型、指标和风险 | 服务目录校验、指标真实性校验、风险不得降低 |
| `ToolPlanningAgent` | 规划调查步骤 | 从动态工具目录选择相关工具 | 工具白名单、去重、强制保留基础调查工具 |
| `EvidenceCollectorAgent` | 执行调查 | 工具同时暴露为 Spring AI Tool Calling 能力 | 只执行已注册只读工具，单工具失败不阻断全局 |
| `RootCauseAgent` | 综合根因 | 基于真实证据生成候选、置信度和推理 | 校验 evidenceId、过滤失败证据、限制置信度 |
| `ResponsePlanAgent` | 生成处置方案 | 根据根因和证据提出补充动作 | 权限、风险、审批、危险文本和观察指标白名单 |
| `SupervisorAgent` | 编排完整链路 | 触发最终流式复核 | 固定执行顺序、阶段事件、异常隔离和报告组装 |

## Mock 运维工具

当前提供六个只读工具，可以稳定演示完整证据链：

- `service-status`：服务状态、健康实例数和可用率。
- `error-log`：关键错误日志。
- `resource-usage`：CPU、内存、错误率和 P99 延迟。
- `deployment`：当前版本、上一稳定版本和最近变更。
- `database-connection`：连接池容量、活跃连接和等待线程。
- `dependency-status`：下游依赖健康情况。

工具实现通过 Spring Bean 动态形成 AI 可见目录。模型无法调用没有注册的工具，也无法通过自由文本
创造诸如 `delete-production` 之类的写操作工具。

## 安全边界

- 模型不可用、超时、结构化转换失败时，各 Agent 自动退回完整 Java 规则方案。
- 主模型失败后由备用模型接管；两个 Provider 分别拥有独立的重试、限流和熔断状态。
- AI 指标值必须真实出现在用户原始告警中。
- AI 根因必须引用本轮真实且非 `FAILED` 的 `evidenceId`。
- 单证据根因置信度最高为 `0.65`，双证据最高为 `0.80`，三项及以上最高为 `0.95`。
- Java 确定的风险、回滚和升级结论不能被模型降低或删除。
- AI 处置动作必须使用固定动作类型、紧急程度和责任角色。
- 回滚、扩容、修改配置、重启、限流、降级、摘除实例和切换流量必须明确经过人工确认。
- `rm -rf`、危险 SQL、删除生产数据、关闭数据库、绕过审批和禁用审计等内容会被拒绝。
- 系统当前没有生产写工具，页面只生成建议，不会自动修改生产环境。
- 只有模型成功返回并完成结构化转换后，本轮对话才会写入 Chat Memory；重试和主备切换不会重复污染记忆。

## SSE 事件协议

`POST /api/v1/alerts/analyze/stream` 返回 `text/event-stream`。每个 SSE `data` 使用统一信封：

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
- `type`：与协议层 `event:` 一致。
- `data`：当前事件的业务载荷。

事件类型包括：

- `progress`：Java 编排阶段进度。
- `ai-token`：最终 AI 复核的文本增量，用于前端逐字展示。
- `recognition`、`evidence`、`root-cause`、`action`、`ai-review`：已经完整生成的报告区段。
- `report`：最终完整 `IncidentReport`。
- `error`：连接建立后的安全错误信息。

AI 载荷中的 `chunkSequence` 只表示当前模型尝试内的文本片段顺序。重试或切换备用模型时会归零，
不等同于 Run 级 `seq`。

## HTTP 接口

| 方法与路径 | 说明 |
|---|---|
| `POST /api/v1/alerts/analyze` | 同步返回完整 JSON 报告 |
| `POST /api/v1/alerts/analyze/stream` | 使用 SSE 返回进度、报告区段、AI 增量和最终报告 |
| `POST /api/v1/alerts/analyze/markdown` | 生成可下载的 Markdown 运维报告 |
| `GET /actuator/health` | 健康检查 |
| `GET /actuator/prometheus` | Prometheus 指标 |
| `GET /swagger-ui/index.html` | OpenAPI 调试页面 |

同步接口示例：

```bash
curl -X POST http://localhost:8080/api/v1/alerts/analyze \
  -H 'Content-Type: application/json' \
  -d '{
    "alertText": "支付服务刚发布后大量请求超时，错误率18.7%，用户支付失败",
    "conversationId": "demo-conversation"
  }'
```

首次请求可以不传 `conversationId`，服务端会自动生成。连续追问时，应继续传回上一份报告中的
`conversationId`。

## 环境要求

- JDK 17 或更高版本。
- Maven 3.9，或者直接使用仓库内 Maven Wrapper。

## 本地启动

```bash
./mvnw spring-boot:run
```

默认启用 `local` Profile：

- 使用文件型 H2 数据库。
- 使用 `mock-primary` 和 `mock-backup`。
- Mock 模型仍然经过真实的 Spring AI `ChatClient`、Structured Output、Chat Memory、主备路由和
  Resilience4j 链路，但不会访问外部模型，也不会消耗额度。

启动后打开：

- 页面：http://localhost:8080
- Swagger：http://localhost:8080/swagger-ui/index.html
- Prometheus：http://localhost:8080/actuator/prometheus

## 连接真实模型

`prod` Profile 默认使用 DeepSeek 作为主模型、阿里百炼兼容接口作为备用模型。密钥只通过环境变量
传入，不应写进代码、配置文件或 Git：

```bash
export SPRING_PROFILES_ACTIVE=prod
export DATABASE_URL='jdbc:h2:file:./data/ops-alert-assistant-prod'
export DATABASE_USERNAME='sa'
export DATABASE_PASSWORD=''
export AI_DEEPSEEK_API_KEY='your-deepseek-key'
export AI_BAILIAN_API_KEY='your-bailian-key'
./mvnw spring-boot:run
```

可通过 `AI_PRIMARY_PROVIDER`、`AI_BACKUP_PROVIDER`、各 Provider 的 `BASE_URL`、`MODEL`、
`TEMPERATURE`、`MAX_TOKENS` 和 `TIMEOUT` 环境变量覆盖默认配置。项目默认只携带 H2 驱动；如果
将 `DATABASE_URL` 改成 MySQL 或 PostgreSQL，还需要在 `pom.xml` 中增加相应 JDBC 驱动。

## 测试

```bash
./mvnw clean test -Dspring.profiles.active=test
```

当前测试覆盖领域模型、六个工具、多 Agent 编排、Spring AI Structured Output、主备路由、重试、
限流、熔断、Chat Memory、SSE 协议、前端页面、Markdown 报告，以及模型虚构服务、工具、证据、
指标和危险处置动作时的 Java 安全兜底。

## 主要目录

```text
src/main/java/com/enterprise/opsassistant
├── agent          多 Agent 业务编排与 Java 安全规则
├── ai             Spring AI Client、结构化输出、主备路由和 Chat Memory
├── api            REST、SSE 和统一异常响应
├── catalog        企业服务目录及别名解析
├── config         AI Provider、线程池和应用配置
├── domain         不可变领域模型
├── mock           Mock 运维数据源
├── observability  Micrometer 指标
├── report         Markdown 报告生成
└── tool           六个只读运维工具及动态工具目录
```

## 可观测性

- 每次分析使用 `analysisId` 贯穿日志 MDC `traceId`。
- 工具调用记录工具名、服务名、状态和耗时。
- AI 日志记录 Provider、模型、是否使用备用模型和结构化候选数量。
- Micrometer 记录分析成功/失败、风险等级、工具状态、AI Provider 和调用耗时。
- Prometheus 可通过 `/actuator/prometheus` 拉取指标。
