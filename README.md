# trace-log-metrics-alerting-java

请求链路追踪 + 结构化日志/脱敏 + 内存窗口指标 + 阈值告警（滞回状态机）的一体化演示工程。
纯内存实现，不依赖外部数据库 / 消息中间件，适合本地验证与单元测试。

## 1. 关联标识（traceId）

- 传递头：`X-Trace-Id`（请求头 / 响应头同名）。
- 合法规则（`TraceIdValidator`）：
  - 外部透传：`^[0-9a-f]{32,64}$`（32~64 位小写十六进制）；
  - 本服务生成：`tl` + 38 位小写十六进制，共 40 位（毫秒时间戳 + 随机数，`TraceIdGenerator`）。
- 处理语义（`TraceIdFilter`，过滤器链最前端）：
  - 请求头**缺失**：生成新标识，绑定上下文，回写响应头（日志中 `traceIdSource=generated`）；
  - 请求头**合法**：原样沿用并回写（`traceIdSource=incoming`）；
  - 请求头**非法**：确定性拒绝，不静默接受、不静默替换：
    HTTP `400`，响应体 `{"traceId":"invalid","category":"VALIDATION","code":"INVALID_TRACE_ID",...}`，
    访问日志 `traceIdSource=rejected`，并输出一条 `INVALID_TRACE_ID` 错误日志。
- 传播机制：
  - 请求线程：`TraceContext`（`InheritableThreadLocal`）+ SLF4J `MDC`；
  - 异步线程：`TraceAwareTaskDecorator` 在提交时捕获、执行前绑定、执行后清理，
    业务异步逻辑注入名为 `traceTaskExecutor` 的 `Executor`（见 `AsyncConfig`）；
  - Servlet 异步（`CompletableFuture` 返回值）：过滤器放开 ASYNC 派发，
    在异步完成派发时重新绑定并输出该请求唯一的一条访问日志，traceId 以 request 属性为准；
  - 线程池复用场景下工作线程执行完毕即清理，防止串号。

## 2. 结构化日志与脱敏

日志为单行 JSON，两类 logger：`ACCESS`（访问/阶段日志）与 `ERROR`（异常日志）。

访问日志字段：

| 字段 | 含义 |
| --- | --- |
| `timestamp` | ISO-8601 时间 |
| `traceId` | 关联标识 |
| `logType` | `access` / `stage` / 错误日志为 `error` |
| `method` `path` | HTTP 方法与路径 |
| `status` | HTTP 状态码 |
| `durationMs` | 请求耗时（毫秒，异步请求按整个异步生命周期计） |
| `errorCategory` | `NONE` / `VALIDATION` / `BUSINESS` / `SYSTEM` |
| `errorCode` | 错误码（如 `INVALID_TRACE_ID`、`ORDER_NOT_ALLOWED`、`INTERNAL_ERROR`） |
| `traceIdSource` | `generated` / `incoming` / `rejected` |
| `queryParams` | 脱敏后的查询参数 |

错误日志额外包含 `exceptionClass` 与 `message`（脱敏后）。

三类错误可区分（`GlobalExceptionHandler`）：

- 参数/标识校验失败：HTTP 400，`VALIDATION`；
- 业务拒绝：HTTP 422，`BUSINESS`（`BusinessException` + 业务错误码）；
- 系统异常：HTTP 500，`SYSTEM`。

脱敏范围（`SensitiveDataMasker`，统一替换为 `***`）：

- 请求头 / 参数中键名（大小写不敏感，`_`/`-` 词根子串匹配）含
  `password`、`passwd`、`pwd`、`secret`、`token`、`authorization`、
  `api key/apikey/api_key`、`credential(s)`、`access-key`、`private-key`、`session` 的值；
- 任意日志文本中的 `password=xxx`、`token: xxx`、`Authorization: xxx`、
  `Bearer <jwt>`、`Basic <base64>` 及裸 JWT（`aaa.bbb.ccc` 形态）；
- 异常消息在写入错误日志前统一过脱敏器；非敏感字段（如 `username`、`orderId`）保留。

## 3. 指标与告警语义

### 3.1 窗口指标（`InMemoryMetricsRegistry`，纯内存）

- 固定大小翻滚窗口，边界对齐 epoch：`windowStart = floor(now / windowSize) * windowSize`；
  同一条请求在任何写入方看来都只属于一个窗口。
- 计数使用 `LongAdder`，窗口对象惰性创建且永不替换：并发写入不重复、不遗漏。
- 每窗口记录：请求数、失败数（三类错误均算失败）、成功数、耗时总和；
  可派生失败率与平均耗时（`WindowSnapshot`）。
- 查询最近 N 个窗口时，**无流量窗口按真实边界补零**返回（不会“查不到”，也不会伪造数据）；
  早于保留期的窗口被淘汰。

### 3.2 告警（`AlertService` + 定时评估 `AlertScheduler`）

规则（`AlertRule`）要素：指标类型（`FAILURE_RATE` / `AVG_DURATION_MS` / `REQUEST_COUNT`）、
聚合窗口数、触发阈值、恢复阈值、最小样本数 `minSamples`。

状态机 `OK -> FIRING -> OK`，带滞回（hysteresis）：

- **触发**：当前 `OK` 且 样本数 `>= minSamples` 且 指标值 `>= fireThreshold`；
- **恢复**：当前 `FIRING` 且 样本数 `>= minSamples` 且 指标值 `< resolveThreshold`（严格小于）；
- 滞回带 `[resolveThreshold, fireThreshold)` 内状态保持不变，阈值附近抖动不反复触发/恢复；
- **无流量 / 样本不足绝不触发**；FIRING 期间进入静默（无数据）不自动恢复，
  必须出现“样本充足且指标低于恢复阈值”的评估才恢复——无数据不等于恢复；
- 每次状态迁移生成 `AlertEvent`（迁移前后状态、观测值、样本数、评估时刻、依据窗口范围），
  当前状态与事件历史均可查询；单规则评估串行化，并发评估只产生一次迁移。

默认规则：

- `high-failure-rate`：聚合 3 个窗口，失败率 `>=0.6` 触发、`<0.3` 恢复，至少 10 样本；
- `slow-latency`：聚合 3 个窗口，平均耗时 `>=300ms` 触发、`<150ms` 恢复，至少 10 样本。

## 4. 配置项（`application.properties`）

| 配置项 | 默认值 | 含义 |
| --- | --- | --- |
| `observability.metrics.window-size-millis` | `10000` | 窗口大小（毫秒） |
| `observability.metrics.retention-windows` | `12` | 内存保留窗口数 |
| `observability.alert-evaluate-interval-ms` | `5000` | 告警定时评估间隔（毫秒） |

默认告警规则在 `ObservabilityConfig#defaultAlertRules()` 中声明（增加规则：新增一个
`AlertRule` Bean 或调用 `AlertService#registerRule`）。

## 5. 接口

业务演示：

- `GET /api/hello?name=...`：同步成功；`GET /api/hello-async?name=...`：异步两阶段处理；
- `GET /api/reject?orderId=...`：缺参触发 400/VALIDATION，`orderId=X*` 触发 422/BUSINESS；
- `GET /api/boom`：500/SYSTEM（异常消息含 token，演示脱敏）；
- `POST /api/echo?username=...&password=...&token=...`（可带 `Authorization` 头）：脱敏演示。

本地观测接口（无需引入 spring-boot-actuator）：

- `GET /actuator-local/metrics/windows?count=6`：最近窗口明细（无流量窗口为全零）；
- `GET /actuator-local/metrics/aggregate?count=3`：最近 N 窗口聚合；
- `GET /actuator-local/alerts` / `GET /actuator-local/alerts/{name}`：告警状态与迁移历史；
- `POST /actuator-local/alerts/evaluate`：立即触发一次评估（不必等待定时周期）。

## 6. 本地验证

```bash
mvn test
```

49 个用例全部通过。运行日志中 `BUSINESS_COVERAGE` logger 会打印每个业务域的
用例开始 / 通过 / 失败，可直接看出覆盖了哪些业务：

- 关联标识：生成唯一性、合法/非法校验、异步继承与清理、并发不串号（重复并发压测）；
- 脱敏：敏感键名变体、`password=/token:/Bearer/Basic/JWT`、参数表、异常消息；
- 窗口指标：边界对齐、无流量补零、过期淘汰、并发计数精确、跨窗口聚合；
- 告警：无流量不误报、样本不足不触发、触发/恢复迁移及依据、滞回带防抖、
  静默期不自动恢复、并发评估单次迁移、耗时规则；
- 端到端（MockMvc）：traceId 生成回写 / 合法沿用 / 非法拒绝、三类错误区分、
  异步各阶段同标识且每请求恰好一条访问日志、请求头/参数/异常消息不落敏感原文。

手工验证（启动后端口随机，见启动日志）：

```bash
curl -i http://localhost:<port>/api/hello
curl -i -H 'X-Trace-Id: 0123456789abcdef0123456789abcdef' http://localhost:<port>/api/hello
curl -i -H 'X-Trace-Id: BAD-ID' http://localhost:<port>/api/hello
curl 'http://localhost:<port>/api/boom' >/dev/null 2>&1 || true
curl -X POST 'http://localhost:<port>/api/echo?username=alice&password=hunter2&token=t1' \
  -H 'Authorization: Bearer abc.def.ghi'
curl -X POST http://localhost:<port>/actuator-local/alerts/evaluate
curl 'http://localhost:<port>/actuator-local/metrics/windows?count=3'
```
