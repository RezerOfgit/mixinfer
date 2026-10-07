# ADR-003: Multi-Endpoint Routing and Reliability

- **Status**: Proposed
- **Date**: 2026-10-07
- **Deciders**: MixInfer Author
- **Related**: [ADR-001](./001-use-semantic-ir.md), [ADR-002](./002-streaming-data-plane.md), [architecture.md](../architecture.md)

## Context

V0.2 的路由模型是：

```
logical model → single Endpoint
```

`ModelRouter` 内部持有 `Map<String, Endpoint>`，一个模型对应一个上游入口。这意味着：

1. 上游挂了整个请求失败，没有任何回退路径。
2. 无法灰度、无法主备、无法 A/B 测试。
3. 单点故障无法被网关层吸收。

V0.3 的核心目标是让 MixInfer 具备"多上游 + 故障转移"能力。这引出三个必须回答的问题：

### 问题 1：如何表达"一个模型对应多个上游"？

配置里必须能够声明：

```
deepseek-chat → [primary, backup]
```

问题是：

- 是加权随机还是按顺序？
- 是否需要显式的 `priority` / `weight` 字段？
- 声明顺序本身能不能承担"优先级"的语义？

### 问题 2：哪些失败可以触发 fallback？

V0.2 的 `ProviderException` 是一个笼统的异常类型，它可能来自：

- 上游连接失败（`ConnectException`）
- 上游超时（`SocketTimeoutException`）
- 上游返回 401 / 400 / 500 / 503
- 上游返回了非法响应体

这些失败的可恢复性是**完全不同**的：

- **401 / 400** —— 换上游也没用，是请求本身的问题
- **连接失败 / 5xx / 429** —— 换上游可能成功
- **请求已发送但连接断开** —— 上游**可能已经执行了**，fallback 有重复计费风险

如果用 `instanceof ProviderException` 统一判定，会把"应重试"和"不应重试"的情况混在一起。

### 问题 3：流式场景下 fallback 的边界在哪？

非流式请求：

```
request → upstream → response → done
```

Fallback 的时机比较容易定义。

但流式请求是：

```
request → upstream → SSE 头 → chunk 1 → chunk 2 → ... → chunk N
                            ↑
                      响应已经提交
```

一旦 SSE 头发出，客户端已经收到了数据。如果此时上游断流，**不能切换到另一个上游重发**——否则客户端会收到两段拼接的输出，语义破碎。

所以流式场景下，Fallback 窗口必须明确定义。

## Decision

### Decision 1: V0.3 支持多 Endpoint 路由；声明顺序即优先级

`ModelRouter.route(model)` 的返回类型从 `Endpoint` 变为 `List<RouteTarget>`。

```java
/**
 * A single routing entry for a logical model.
 *
 * <p>In V0.3.0 a RouteTarget holds only the resolved Endpoint; the
 * declaration order of multiple targets carries the priority semantics.
 *
 * <p>RouteTarget exists as a separate abstraction from Endpoint so that
 * future routing metadata (weight, priority, tags, conditional matching)
 * can be added without changing Endpoint or the Router's contract.
 */
@Value
@Builder
public class RouteTarget {
  Endpoint endpoint;
}
```

**V0.3.0 不在 `RouteTarget` 中引入 `priority` 或 `weight` 字段。**

**理由**：

- `priority` 由**声明顺序**隐式表达。YAML 里 A 在前 B 在后，语义已经足够清晰。
- `weight` 属于"流量分配"问题，不是"故障转移"问题。引入权重会导致一系列未决问题（随机方式？加权轮询？健康节点如何参与？fallback 后是否重新随机？），超出 V0.3 的目标范围。
- 字段一旦加入就会成为负担。未来真需要时再引入，是**演进**而不是**预设计**。

配置形态：

```yaml
mixinfer:
  routes:
    - model: deepseek-chat
      targets:
        - provider: deepseek-primary
        - provider: deepseek-backup
```

**向后兼容**：原 `provider: xxx` 单字段形式继续可用，视为只有一个 target。

**推荐**：V0.3 之后的新配置应使用 `targets` 格式。`provider` 单字段形式仅为向后兼容保留，不在文档中作为主推格式。

### Decision 2: 路由、健康过滤、选择、执行是四个独立的关注点

数据流必须是：

```
ModelRouter.route(model)
        ↓ List<RouteTarget>（原始候选）
EndpointHealthTracker.filter(candidates)
        ↓ List<RouteTarget>（过滤掉不健康的）
EndpointSelector.order(filtered)
        ↓ List<RouteTarget>（最终有序候选）
FailoverExecutor.execute(ordered)
        ↓ 结果或抛异常
```

**每个阶段只做一件事**：

| 阶段 | 职责 | 不负责 |
|---|---|---|
| Router | 从配置解析出候选列表 | 不感知健康状态、不做选择 |
| HealthTracker | 过滤掉不健康的候选 | 不排序、不感知配置 |
| Selector | 对候选排序 | 不感知健康、不执行 |
| Executor | 按顺序尝试，失败时切换 | 不做路由、不做健康判断 |

**理由**：

- 如果 `ModelRouter` 直接管理健康状态，它就会变成"路由 + 健康 + 选择"的大杂烩。V0.4 引入成本感知路由时无处安放。
- Selector 作为独立 SPI，V0.3.1 可以加 `WeightedRandomSelector`，V0.4 可以加 `CostAwareSelector`，不需要改 Router。
- HealthTracker 独立后，未来可以做分布式健康状态（多实例共享 Redis）而不影响其他组件。

**V0.3.0 只提供一个 Selector 实现**：`SequentialSelector`（即按声明顺序返回，不做任何变换）。它存在的意义是**锁定 SPI**，而不是提供算法。

### Decision 3: 失败按 FailureType 分类，不按 Java 异常类型

不把 `Set<Class<? extends Exception>>` 暴露到配置——Java 异常类不是可靠性策略的合适抽象。

```java
public enum FailureType {
    // 可跨 Endpoint failover
    CONNECTION_FAILURE,      // Connect refused, DNS, TLS handshake
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    HTTP_408,                // Request Timeout —— 可 failover
    HTTP_5XX,
    HTTP_429,

    // 不可 failover —— 换上游也无效
    AUTHENTICATION_FAILURE,  // 401, 403
    INVALID_REQUEST,         // 400
    MODEL_NOT_FOUND,         // 404

    // 语义不明确，保守处理为不可 failover
    UNKNOWN
}
```

```java
public interface FailureClassifier {
    FailureType classify(Throwable error);
}
```

**只有 `CONNECTION_FAILURE`、`CONNECT_TIMEOUT`、`READ_TIMEOUT`、`HTTP_408`、`HTTP_5XX`、`HTTP_429` 触发 failover。**

**`UNKNOWN` 一律不 failover**——见 Decision 5。

### Decision 4: Failover 只在上游响应提交之前允许

**非流式**：

Fallback 窗口是整个请求。只要没有拿到 `LlmResponse`，都可以切换到下一个 Endpoint。

**流式**：

Fallback 窗口**仅限**：

```
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

**一旦 `LlmStream` 被成功创建（等价于 SSE 头即将写出），fallback 关闭。**

```
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

**代码级锚点**：实现上，`LlmStream` 创建成功后的第一个
`streamingWriter.writeData()` 调用等价于"响应已提交"。Failover 判定以
`responseCommitted` 标志位为准（与 ADR-002 的实现一致）。在
`ChatCompletionService.handleStream` 中，`responseCommitted` 一旦置为
true，任何后续异常都不再触发 failover，只触发结算状态记录和上游取消。

**理由**：客户端已经收到部分输出时切换上游，会产生两段拼接的响应（A 说"你好，我认为……"然后断流，B 说"这个问题我认为……"），语义上不可接受。

**中途断流时**：

- 向客户端发送 SSE error 事件（V0.2 已实现）
- 记录 UsageRecord（`settlementStatus=PARTIAL/UNKNOWN`，`reason=upstream_stream_error`）
- **不切换上游**

### Decision 5: 跨 Endpoint Failover 不保证 exactly-once 执行

V0.2 已有结论"客户端断开 ≠ 上游停止计费"。V0.3 需要在 Failover 上建立同样的诚实：

```
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

**跨 Endpoint Failover 并不是"安全重试"**。它只是把恢复路径从"同 Endpoint 重试"扩展到"备用 Endpoint"。

**因此 V0.3 的边界是**：

1. **只在明确的上游连接建立失败阶段 failover** —— 此时可以合理推断请求未到达上游
2. **对歧义失败（UNKNOWN）不 failover** —— 保守优先于可用性
3. **对已经开始向客户端输出的请求禁止 failover**

**核心原则**：**可靠性不能以牺牲正确性为代价。** 宁可返回失败，不要返回可能重复计费的结果。

### Decision 6: 健康追踪是内存级、实例级、顾问性的

`EndpointHealthTracker` 是 V0.3 的**优化**，不是**正确性要求**。

**规则**：

- **存储**：`ConcurrentHashMap<EndpointId, HealthState>`，进程内状态
- **失败计数**：连续失败 N 次标记为 unhealthy
- **成功重置**：任意一次成功将 failureCount 归零
- **冷却恢复**：unhealthy 持续 T 秒后自动恢复为 healthy
- **兜底**：**当所有候选都被标记为 unhealthy 时，返回全部候选而不是空列表**

**最后一条是 Decision 6 的核心**：

> HealthTracker 只能减少失败概率，不能导致"没有 Endpoint 可用"。

因为健康状态可能因为 bug、重启、网络抖动全部变 unhealthy。如果此时返回空列表，MixInfer 会因为"自己认为所有上游都挂了"而瘫痪——**反而比没有 HealthTracker 更差**。

**V0.3 不引入 Redis / DB / 分布式健康状态**。多实例各自维护自己的 health，互相独立。

**配置**：

```yaml
mixinfer:
  health:
    enabled: true
    failure-threshold: 3
    cooldown-seconds: 30
```

### Decision 7: 超时模型区分 connect / request / idle，不做统一 lifetime

V0.2 只有单一的 `timeout` 字段。V0.3 拆分为：

| 超时类型 | 适用场景 | 语义 | 默认值 | V0.3 |
|---|---|---|---|---|
| `connectTimeout` | 所有请求 | 建立 TCP/TLS 连接的时间上限 | 5s | ✅ 实现 |
| `requestTimeout` | 非流式 | 从发送请求到拿到完整响应的时间上限 | 120s | ✅ 实现 |
| `idleTimeout` | 流式 | 相邻两个 chunk 之间的最大间隔 | 30s | ✅ 实现 |
| `streamLifetime` | 流式 | 单次流式请求的总时长上限 | — | ❌ 不实现 |

`requestTimeout` 的 120s 是针对常见模型的经验值。长上下文或推理模型的使用者应显式调高该值。

**关键区分**：

- `idleTimeout` 检测"流卡住"——模型 30 秒不发 chunk，说明流异常
- `streamLifetime` 检测"流太长"——防止无限生成
- 两者是**不同的概念**，不能混用

**V0.3 实现 `connectTimeout`、`requestTimeout`（非流式）、`idleTimeout`（流式）。**
`streamLifetime` 留到 V0.3.1 或 V0.4，因为它的默认值很难定（模型可以生成 10 分钟，强行切断会打断正常请求）。

## Consequences

### Positive

- **单点故障可恢复**：上游临时故障会被自动绕过
- **配置驱动**：新加备份上游不需要改代码
- **关注点分离**：Router / Health / Selector / Executor 各自独立，未来演进不互相污染
- **失败语义明确**：FailureType 枚举是可靠性策略的**可读表达**
- **流式边界明确**：Failover Window 是白纸黑字的规则，不是"看情况"

### Negative

- **配置复杂度上升**：从单 provider 变为 targets 列表
- **可观测性要求上升**：需要记录"哪个 Endpoint 失败、为什么失败、是否 fallback"
- **重复计费风险**：跨 Endpoint failover 可能产生双倍计费（已通过窗口限制缓解，未完全消除）
- **内存状态**：多实例部署时健康状态不一致（V0.3 接受）

### Neutral

- `ModelRouter` 的返回类型从 `Endpoint` 变为 `List<RouteTarget>`，调用方需要相应改造
- 未来引入 WeightedRandom / CostAware 选择器时，`EndpointSelector` SPI 已经就位

## Alternatives Considered

### Alternative 1: 用 `weight` + `priority` 一起建模

- **优点**：一次设计到位，V0.3.1 无需改结构
- **拒绝理由**：
    - `weight` 属于流量分配，`priority` 属于故障转移，两者是不同问题
    - 一旦引入 `weight`，需要回答"随机方式"、"健康节点如何参与"、"fallback 后是否重新随机"等一系列未决问题
    - 声明顺序已经能表达 `priority`，无需显式字段
- **演进路径**：V0.3.1 需要时再引入 `weight` 字段；V0.4 引入 `priority` 字段

### Alternative 2: 用 `instanceof ProviderException` 判定是否 failover

- **优点**：实现最快
- **拒绝理由**：
    - `ProviderException` 是一个笼统的异常类型，包含"应重试"和"不应重试"两类失败
    - 401 / 400 也是 ProviderException，但换上游无效
    - 无法表达"歧义失败保守处理"的语义

### Alternative 3: 同 Endpoint 重试 + 跨 Endpoint Fallback 都做

- **优点**：可靠性更强
- **拒绝理由**：
    - LLM 请求**不具备通用幂等性**。同 Endpoint 重试可能重复计费
    - 重试与 Fallback 是两个独立的可靠性维度，V0.3 只做 Fallback
    - 减少概念数量，降低配置负担

### Alternative 4: 使用 Circuit Breaker（熔断器）

- **优点**：成熟的可靠性模式；状态机明确
- **拒绝理由**：
  - 完整的 Circuit Breaker 需要 half-open 状态和主动探测。在
    HealthTracker 里做 half-open 等价于"定时向每个 unhealthy 的
    Endpoint 发探测请求"，这在 LLM 场景下会产生费用——**探测请求
    也是真实调用**。
  - HealthTracker 的冷却恢复机制（T 秒后自动恢复为 healthy）是
    **零成本的替代方案**：不需要额外的探测流量，只需要等下一次真实
    请求来验证。
  - 引入 Circuit Breaker 库会与 HealthTracker 职责重叠，需要额外的
    协调设计。
  - 这不是"没时间做"，而是"做了反而有副作用"。

### Alternative 5: 用 Redis 做分布式健康状态

- **优点**：多实例共享状态
- **拒绝理由**：
    - V0.3 不做 Control Plane，也不做分布式协调
    - 引入 Redis 会带来部署依赖、网络故障、序列化等问题
    - 单实例健康状态已经能覆盖 V0.3 的目标场景

### Alternative 6: 流式中途断流时切换上游

- **优点**：表面上看恢复能力更强
- **拒绝理由**：
    - 客户端已经收到部分输出，切换上游会导致响应拼接
    - 语义上不可接受——"你好，我认为这个问" + "题是错的"会被用户理解为模型说了两段话
    - 保守优先于可用性

## References

- [OpenAI API Error Codes](https://platform.openai.com/docs/guides/error-codes)
- [Circuit Breaker Pattern — Martin Fowler](https://martinfowler.com/bliki/CircuitBreaker.html)
- [Fallacies of Distributed Computing](https://en.wikipedia.org/wiki/Fallacies_of_distributed_computing)
- [Idempotency in Distributed Systems](https://en.wikipedia.org/wiki/Idempotence)