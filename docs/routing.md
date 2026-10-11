# MixInfer 路由设计

本文档描述 V0.3 引入的多 Endpoint 路由与故障转移机制。
面向想理解可靠性语义的读者，与 [ADR-003](adr/003-multi-endpoint-routing.md) 互补。

## 1. 目标

V0.1-V0.2 的路由模型是"一个逻辑模型对应一个上游"。V0.3 引入：

- 一个模型可对应多个上游
- 主路失败时自动切换到备路
- 健康状态影响候选过滤
- 失败按语义分类决定是否 failover

## 2. 路由准备管线

```text
RoutePlanner.plan(model)
    = ModelRouter.route(model)
    → EndpointHealthTracker.filter(candidates)
    → EndpointSelector.order(healthy)
```

**职责分离**：

| 阶段 | 职责 | 不负责 |
|---|---|---|
| Router | 从配置解析出候选列表 | 不感知健康、不排序 |
| HealthTracker | 过滤掉不健康的候选 | 不排序、不感知配置 |
| Selector | 对候选排序 | 不感知健康、不执行 |

**执行阶段不抽公共抽象**。流式与非流式的 failover 语义差异过大：

| 维度 | 非流式 | 流式 |
|---|---|---|
| 失败后重试 | 任何时候 | 仅 open 阶段 |
| 返回类型 | `LlmResponse` | `LlmStream` + 逐块转发 |
| 与客户端交互 | 无 | 需要 `HttpServletResponse`、flush |
| commit 概念 | 不存在 | `responseCommitted` 是硬边界 |
| 取消上游 | 不需要 | `stream.close()` 是 MUST |

## 3. Failover 窗口

### 3.1 非流式

整个请求期间都可 failover——只要没拿到 `LlmResponse`，都能切换到下一个。

### 3.2 流式

```text
select endpoint
      ↓
open upstream connection
      ↓
receive response headers
      ↓
create LlmStream         ← 窗口结束
      ↓
downstream response committed
      ↓
read chunks
      ↓
finish / disconnect
```

```text
┌──────────────────────────┐
│      Failover Window     │
│                          │
│ connect                  │
│ headers                  │
│ protocol validation      │
└──────────────┬───────────┘
               ↓
         LlmStream created
               ↓
            NO FALLBACK
```

**代码级锚点**：`LlmStream` 创建成功后的第一个
`streamingWriter.writeData()` 调用等价于"响应已提交"。
`responseCommitted` 一旦置为 true，任何后续异常都不再触发 failover。

**理由**：客户端已经收到部分输出时切换上游，会产生两段拼接的响应
（A 说"你好，我认为……"然后断流，B 说"这个问题我认为……"），语义上不可接受。

## 4. 失败分类

失败按 `FailureType` 分类：

```java
public enum FailureType {
    // 可跨 Endpoint failover
    CONNECTION_FAILURE,      // Connect refused, DNS, TLS handshake
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    HTTP_408,
    HTTP_5XX,
    HTTP_429,

    // 不可 failover —— 换上游也无效
    AUTHENTICATION_FAILURE,  // 401, 403
    INVALID_REQUEST,         // 400
    MODEL_NOT_FOUND,         // 404

    // 语义不明确，保守处理
    UNKNOWN
}
```

**分类规则**：

1. **HTTP 状态码优先**——若 `ProviderException.httpStatus` 存在，直接映射
2. **异常 cause chain 遍历**——若 status 不存在，遍历 cause chain 找根因
   （`ConnectException` / `UnknownHostException` / `HttpConnectTimeoutException` 等）
3. **兜底 UNKNOWN**——无法分类时保守处理，不 failover

**为什么 `UNKNOWN` 不 failover**：见下节。

## 5. 不保证 exactly-once

**跨 Endpoint Failover 并不是"安全重试"。**

```text
Endpoint A
请求已发出
连接断开
Gateway 不知道 A 是否已开始执行
        ↓
切换到 Endpoint B
        ↓
A 可能已经产生一次计费
B 又产生一次计费
```

**V0.3 的边界**：

1. 只在明确的上游**连接建立失败阶段** failover——可合理推断请求未到达上游
2. 对歧义失败（`UNKNOWN`）**不 failover**——保守优先于可用性
3. 对已经开始向客户端输出的请求**禁止 failover**

**核心原则**：可靠性不能以牺牲正确性为代价。宁可返回失败，不要返回可能重复计费的结果。

## 6. 健康追踪

`EndpointHealthTracker` 是内存级、实例级的**顾问性**组件。

**规则**：

- **存储**：`ConcurrentHashMap<providerName, HealthState>`
- **失败计数**：连续失败 N 次标记 unhealthy
- **成功重置**：任意一次成功将 failureCount 归零
- **冷却恢复**：unhealthy 持续 T 秒后自动恢复为 healthy
- **兜底**：全部候选 unhealthy 时**返回全部**而不是空列表

**最后一条是核心**：

> HealthTracker 只能减少失败概率，不能导致"没有 Endpoint 可用"。

因为健康状态可能因为 bug、重启、网络抖动全部变 unhealthy。如果返回空列表，
MixInfer 会因为"自己认为所有上游都挂了"而瘫痪——**反而比没有 HealthTracker 更差**。

**V0.3 不引入 Redis / DB / 分布式健康状态**。多实例各自维护自己的 health。

## 7. 超时模型

V0.3 区分两类超时：

| 超时 | 适用场景 | 语义 | 默认值 |
|---|---|---|---|
| `connectTimeout` | 所有请求 | 建立 TCP/TLS 连接的时间上限 | 5s |
| `requestTimeout` | 非流式 | 从发送请求到拿到完整响应的上限 | 120s |
| `requestTimeout` | 流式 | 整个流的总时长上限 | 120s |

**未实现 idle timeout**：真正的"相邻 chunk 之间的最大间隔"需要异步读取 + 定时器，
V0.3 用 `requestTimeout` 表示总时长上限。V0.3.1 再评估是否引入独立字段。

**per-endpoint 生效**：`connectTimeout` 在 `RestClient` / `HttpClient` 构造时固定，
因此每个 Endpoint 需要独立的 HTTP client 实例。`RestClientFactory` 按 Endpoint 缓存。

## 7. 超时模型

V0.3 区分两类超时：

| 超时 | 适用场景 | 语义 | 默认值 |
|---|---|---|---|
| `connectTimeout` | 所有请求 | 建立 TCP/TLS 连接的时间上限 | 5s |
| `requestTimeout` | 非流式 | 从发送请求到拿到完整响应体 | 120s |
| `requestTimeout` | 流式 | **仅覆盖到上游响应头到达**，不限制整条流的生命周期 | 120s |

**V0.3 不提供流的总生命周期上限。**

JDK `HttpRequest.timeout()` 只覆盖到"响应头返回"。一旦 `BodyHandlers.ofInputStream()`
把 body 流交给调用方，后续的 `InputStream.read()` 不再受该超时约束。也就是说：

```text
0 秒：请求发送
1 秒：收到上游响应头          ← requestTimeout 的作用边界
2 秒：收到第一个 chunk
...
180 秒：模型仍在输出 chunk   ← requestTimeout 不再生效
```

真正的"流最长时长"需要异步读取 + 定时取消，超出 V0.3 的范围，推迟到 V0.3.1。

**per-endpoint 生效**：`connectTimeout` 在 `RestClient` / `HttpClient` 构造时固定，
因此每个 Endpoint 需要独立的 HTTP client 实例。`RestClientFactory` 按 Endpoint 缓存。

## 8. 配置示例

```yaml
mixinfer:
  providers:
    - name: primary
      type: openai-compatible
      base-url: https://api.deepseek.com
      api-key: ${PRIMARY_KEY}
      connect-timeout-seconds: 5
      request-timeout-seconds: 120
    - name: backup
      type: openai-compatible
      base-url: https://api.openai.com/v1
      api-key: ${BACKUP_KEY}

  routes:
    - model: deepseek-flash
      targets:
        - provider: primary
        - provider: backup

  health:
    enabled: true
    failure-threshold: 3
    cooldown-seconds: 30
```

**向后兼容**：旧版单 provider 形式继续可用：

```yaml
routes:
  - model: deepseek-flash
    provider: primary
```

`RouteConfig.resolveTargets()` 会把它解析为单元素列表。