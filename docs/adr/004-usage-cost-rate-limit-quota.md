# ADR-004: Usage Persistence, Cost Calculation, and Rate Limiting

- **Status**: Accepted
- **Date**: 2026-10-13
- **Deciders**: MixInfer Author
- **Related**: [ADR-001](./001-use-semantic-ir.md), [ADR-002](./002-streaming-data-plane.md), [ADR-003](./003-multi-endpoint-routing.md), [architecture.md](../architecture.md)

## Context

V0.3 引入多 Endpoint 路由与 Failover 后，数据平面已经具备基本可靠性。但治理能力仍然薄弱：

- **用量只落到 JSON Lines 文件** —— 能看，但查不了、聚合不了、跨重启不可信
- **没有 Cost** —— 不知道每次请求花了多少钱
- **没有 Rate Limit** —— 一个客户端可以无限打

V0.4 的目标是在**不引入分布式组件**的前提下，把单实例的治理语义做正确。

### 四个能力共享同一条请求生命周期

```text
认证 → 准入（Rate Limit）→ 路由 → 调用 → 结算 → 持久化 → 查询
```

这里有一个**结构性差异**必须提前明确：

- **准入判断发生在请求之前**：必须在调用上游前决定"是否允许这次请求"
- **实际用量只能在请求之后确定**：Token 数、成功与否、成本，都要等上游返回

因此 ADR-004 必须明确区分两类操作：

| 类别 | 时机 | 依据 |
|---|---|---|
| **准入（Admission）** | 请求之前 | 已有历史数据，判断是否放行 |
| **结算（Settlement）** | 请求之后 | 本次请求的实际结果，写入新数据 |

混淆两者会导致"数据库故障时放行一切"或"把尽力而为的记录当作强一致依据"这类问题。

### Quota / Budget 不在 V0.4 范围

V0.4 的 Usage 写入是**尽力而为**的（见决策 1）。如果 UsageRecord 写入失败，而 Quota 依赖这些记录累计判断是否放行，会出现：

```text
请求 A 消耗 1000 tokens → UsageRecord 写入失败
请求 B 到来 → Quota 查询数据库 → 看不到 A → 错误放行
```

**"数据库查询失败时拒绝"不能解决这个问题** —— 数据库是健康的，只是历史记录丢了。

要让 Quota 可靠，必须：

1. 设计独立的额度累计持久化，与 Usage 写入解耦
2. 明确结算与额度更新的事务关系
3. 处理崩溃恢复时的额度一致性

**这是 V0.4 的复杂度预算之外的架构问题。** Quota 顺延到 V0.4.1，前提是上述可靠性问题有明确方案。

### 现有代码的现状（核对于 V0.3 提交）

本 ADR 涉及的新字段**不是当前已有的**，需要 V0.4 引入：

| 字段 | 当前状态 |
|---|---|
| `endpointId` | Endpoint 类**没有**此字段 |
| `upstreamModel` | Endpoint 类**没有**此字段 |
| `costMicrocents` | UsageRecord **没有**此字段 |
| `pricingVersion` | UsageRecord **没有**此字段 |
| `attemptCount` | UsageRecord **没有**此字段 |
| `virtualKeyId` | UsageRecord **没有**此字段 |

现有 `UsageRecord` 有：`requestId` / `model` / `provider` / `startedAt` / `completedAt` / `providerReported` / `settlementStatus` / `usageSource` / `reason`。

**`UsageRecord.model` 当前语义不一致**：

- 非流式成功路径写入 `llmResponse.getModel()`（上游响应模型）
- 流式路径写入客户端请求的 `model`（逻辑模型）

V0.4 统一为**逻辑模型**，上游模型另存 `upstreamModel`。

### 现有配置结构

`MixInferProperties` 当前层级：

- `apiKeys`：客户端 API Key
- `providers`：上游连接配置（`name` / `type` / `baseUrl` / `apiKey` / `models` / 超时）
- `routes`：逻辑模型到上游的映射（`targets[].provider`）
- `health`：被动健康追踪配置

**Provider 与 Endpoint 当前不是独立实体** —— `ProviderConfig` 兼有两者职责，`Endpoint` 是路由时构建的运行时对象。

**本 ADR 不预设 `providers[].endpoints[]` 的嵌套形态** —— 定价配置的具体落点以现有 `MixInferProperties` 结构为基准。ADR 只定义逻辑关系。

### 需要冻结的决策

1. 持久化选型与故障语义
2. UsageRecord 的统计单位与拒绝请求的计数规则
3. Cost 的定价维度与精度
4. Rate Limit 的并发与清理机制
5. Quota 顺延至 V0.4.1
6. 月度窗口的时区与区间
7. Virtual Key 的 ID 模型
8. Admin API 的访问控制
9. 查询 API 的明细 / 聚合分类

### 交付顺序（不在本 ADR 冻结，供实施参考）

```text
P1. Usage 持久化与明细查询       ← 数据链路先做对
P2. Usage 聚合与 Cost 计算       ← 在正确数据上做统计
P3. Virtual Key 与 Rate Limit    ← 请求级准入
（P4. Quota / Budget → V0.4.1）
```

顺序不颠倒。**先把数据链路做正确，再叠加治理规则。**

---

## Decision

### 决策 1：使用 SQLite 作为单实例持久化存储

**驱动**：`org.xerial:sqlite-jdbc`

**连接策略**：

- HikariCP，`maximumPoolSize = 1` —— **以简单性换取并发能力**。单连接意味着应用内部所有数据库操作串行化，这对 V0.4 的预期负载足够。等真实测试显示数据库访问成为瓶颈，再考虑调整连接策略。
- 启用 WAL 模式（`PRAGMA journal_mode = WAL`）—— 降低"读事务阻塞写"的概率。**配合单连接池，WAL 对应用内部的并发收益有限** —— 它的价值主要是为未来可能的多连接方案预留基础。
- `PRAGMA busy_timeout = 5000` —— 锁等待 5 秒后放弃

**明确取舍**：V0.4 用单连接 + WAL 是"简单优先"的选择，不承诺并发写性能。若未来需要更高并发，评估迁移到 PostgreSQL 或调整连接策略。

**Schema 管理**：使用**版本化 SQL Migration**（`V1__init.sql` / `V2__...`），应用启动时执行。不用 `CREATE TABLE IF NOT EXISTS` —— 字段演进时会静默失败。

**事务边界**：每次 UsageRecord 写入是一个独立事务。

**失败语义 —— 需要区分两类**：

| 失败类型 | 处理 |
|---|---|
| **写入失败**（磁盘满、DB 锁定、进程被杀） | 记录 warn，**不影响客户端请求返回** |
| **准入查询失败**（Rate Limit 检查无法完成） | **拒绝新请求**，返回 503 |

**理由**：

- 写入失败是"事实丢失" —— 无法回溯本次请求的用量，但客户端已拿到结果，强行 500 反而更差
- 准入失败是"潜在滥用" —— 放行会让限流形同虚设
- 两类失败的性质不同，处理方式必须不同

**与 V0.4.1 Quota 的关系**：如果未来引入 Quota，**不能**直接依赖这些尽力而为的 UsageRecord 作为额度累计的权威来源 —— 见 Context 中的冲突描述。

**旧日志**：`usage.log` 保留为历史文件，**不自动删除**。V0.4 不实现自动导入（可作为独立脚本，不在本版本范围）。

**数据路径**：`mixinfer.storage.sqlite.path`，默认 `logs/mixinfer.db`。**Docker 必须挂载 volume** —— 否则容器重建会丢失数据。

---

### 决策 2：UsageRecord 以客户端请求为统计单位

一次**客户端请求**对应一条 UsageRecord。**即使请求经过多次 Failover 尝试，也仍然是一条**。

**V0.4 新增 / 变更字段**：

| 字段 | 类型 | 来源 | 说明 |
|---|---|---|---|
| `requestId` | String | 现有 | 关联一次客户端请求 |
| `virtualKeyId` | String | **V0.4 引入** | 治理主体 ID（**不存明文 key**） |
| `model` | String | 现有 | **统一为逻辑模型**（客户端请求的 model） |
| `provider` | String | 现有 | 实际处理成功的上游 Provider 名称 |
| `endpointId` | String | **V0.4 引入** | 实际成功处理请求的 Endpoint 标识 |
| `upstreamModel` | String | **V0.4 引入** | 实际发送给上游的模型标识 |
| `inputTokens` / `outputTokens` | Integer | 现有（从 `providerReported` 拆出） | 输入 / 输出 Token |
| `costMicrocents` | Long（可空） | **V0.4 引入** | 本次成本 |
| `pricingVersion` | String（可空） | **V0.4 引入** | 本次采用的定价配置标识 |
| `settlementStatus` | 枚举 | 现有 | 沿用 V0.2 状态机 |
| `reason` | String | 现有 | 结算不完整或失败的原因 |
| `success` | Boolean | **V0.4 引入** | 请求是否成功 |
| `attemptCount` | Integer | **V0.4 引入** | 实际尝试的上游次数（数值型，用于聚合） |
| `attempts` | String（JSON） | **V0.4 引入** | 每次尝试的诊断明细 |
| `createdAt` | Long（UTC epoch millis） | **V0.4 引入** | 查询、聚合与月度窗口的时间依据 |

**`endpointId` 的定义**：

V0.4 引入一个稳定标识。若现有 `ProviderConfig` 结构无法提供，则由 V0.4 决定其派生规则（例如从 `name` + `baseUrl` 组合派生）。**不得**以可变字段直接作为 ID。

**`upstreamModel` 的定义**：

从上游响应里的 `model` 字段提取。若上游未返回，则为 `null`。

**`model` 语义统一**：

- V0.4 之前：非流式写 `llmResponse.getModel()`（可能为上游模型）
- V0.4 之后：**统一写客户端请求的 `model`**（逻辑模型）
- 上游实际模型记入 `upstreamModel`

**拒绝请求也要记录**：

| 拒绝类型 | 是否记 UsageRecord | `success` | `reason` |
|---|---|---|---|
| 认证失败（未知 Key） | **不记** —— 主体未知 | — | — |
| Rate Limit 拒绝 | 记 | `false` | `rate_limited` |
| 路由失败 | 记 | `false` | `routing_failed` |
| 全部 Endpoint 失败 | 记 | `false` | `all_endpoints_failed` |

**理由**：认证失败是"未识别的主体"，没有 Key 归因；其他拒绝都明确属于某个 Key，不记就无法分析"谁在被拒绝"。

**统计口径**：

聚合结果包含三个独立维度：

| 维度 | 说明 |
|---|---|
| 客户端请求数 | 按 `requestId` 去重，包含被拒请求 |
| 成功请求数 | `success = true` 的请求数 |
| 上游尝试次数 | `SUM(attempt_count)`，**仅用于诊断，不参与客户端用量** |

聚合结果**明确区分**"总请求数"和"成功请求数"，避免混淆。

---

### 决策 3：Cost 定价维度为 Endpoint + upstream model

**不能按逻辑 model 定价**，也**不能只按 Provider 定价**。MixInfer 的架构里，Provider 是逻辑服务，Endpoint 是具体上游地址与凭据。**同一个 Provider 下可能有多个价格不同的 Endpoint**（未来版本）。

**逻辑关系**（本 ADR 只定义关系，具体 YAML 结构以现有 `MixInferProperties` 为基准）：

定价条目绑定 **（Endpoint 标识, upstream model）** 二元组。每条 UsageRecord 记录本次结算所采用的 `endpointId`、`upstreamModel` 和 `pricingVersion`，使成本可追溯。

**精度与币种**：

- 内部表示：`long`，单位 **microcents**（1 cent = 1,000,000 microcents）
- 币种：V0.4 **固定 USD**，字段名或注释标明
- 配置里单价也是 microcents per million tokens（避免浮点）

**计算规则**：

```text
costMicrocents = round(
    inputTokens  * inputPricePerMillionMicrocents  / 1_000_000
  + outputTokens * outputPricePerMillionMicrocents / 1_000_000
)
```

**整数精度约束**：

- **禁止 `double` 中间值**
- 用 `long` 乘法 + 整数除法
- `tokens × pricePerMillion` 用 `long`（避免 `int` 溢出）
- 舍入规则：**四舍五入到 microcents**
- 中间乘积若可能超过 `long` 上限（> 9.2 × 10^18），需分步计算

**`pricingVersion` 的定义**：

必须可复现。两种可选方案：

- 显式版本号：配置里写 `version: 2026-10-12-1`
- 定价配置条目的规范化哈希：对定价条目计算稳定哈希

**未知用量的语义**：

| 场景 | `costMicrocents` | `pricingVersion` | `reason` |
|---|---|---|---|
| 上游返回完整 usage 且有定价 | 计算值 | 定价版本号 | — |
| 上游返回部分 usage 且有定价 | 基于已知部分计算 | 定价版本号 | — |
| 上游未返回 usage | `null` | 定价版本号 | — |
| 该 (endpoint, upstreamModel) 无定价配置 | `null` | `null` | 不覆盖 |

**关键原则**：**未知成本绝不记 0** —— 否则聚合报表会低估成本。

**语义说明**：`costMicrocents` 是**按配置单价估算的成本**，**不是供应商实际账单金额**。供应商可能有缓存折扣、阶梯价或其他调整。字段名或文档中需明确。

**pricing 缺失不写入 `reason`** —— `reason` 仅用于表达"结算不完整或失败"的原因。使用方可由 `success = true && costMicrocents = null` 组合识别"成功但无定价信息"的情况。

---

### 决策 4：Rate Limit 使用滑动窗口，原子操作保证一致性

**数据结构**：

- `ConcurrentHashMap<String, KeyBucket>` —— 外层 Map 的并发由 ConcurrentHashMap 保证
- `KeyBucket` 内部是 `ArrayDeque<Long>` —— **必须**通过 `compute` 或其他同 Key 的原子操作保证准入、清理、bucket 生命周期的协调

**为什么需要同 Key 原子**：`ConcurrentHashMap` 只保证 Map 本身的并发安全，**不保证 Value 内部结构的线程安全**。而且，清理线程删除 bucket 与请求线程读取 bucket 之间的竞争，会导致同一个 Key 短暂拥有两份计数状态。

**准入操作（请求处理）**：

1. 通过 `ConcurrentHashMap.compute(key, ...)` 进入该 Key 的临界区
2. 移除窗口外的时间戳（`(now - windowSize, now]` 之外）
3. 判断当前窗口内计数是否超限
4. **通过准入的请求**才将当前时间戳加入窗口
5. 若被拒，**不加入**窗口时间戳

**被拒绝的请求不计入窗口**：

- **理由**：如果被拒请求也写入窗口时间戳，持续重试的客户端会不断刷新窗口，永远处于"被限流"状态 —— 即使最早的正常请求已经过期。这违背了限流的本意，也导致 `Retry-After` 失去意义。
- **防滥用是独立问题**：如果将来需要限制"反复触发 429 的客户端"，应作为**独立的滥用保护机制**设计（例如基于 IP 的独立桶），而不是混入正常业务限流。

**清理操作（定时任务）**：

1. 通过同一个 Key 的 `compute` 操作进入临界区
2. **仅当**该 Key 的 bucket 没有任何有效窗口内的时间戳时，才删除 bucket
3. **不删除**仍有有效计数的 bucket

**Retry-After**：

- 从**最早的有效准入时间戳**计算剩余窗口时间，向上取整到秒
- 返回给客户端

**拒绝响应**：

- HTTP 429
- Body：

```json
{"error": {"type": "rate_limit_exceeded", "code": "rate_limited"}}
```

- 响应头 `Retry-After: <seconds>`

---

### 决策 5：Quota / Budget 顺延至 V0.4.1

**V0.4 不实现 Quota / Budget。**

**核心问题**：V0.4 的 Usage 写入是**尽力而为**（决策 1）。如果 UsageRecord 写入失败，而 Quota 依赖这些记录累计判断是否放行，会出现：

```text
请求 A 消耗 1000 tokens → UsageRecord 写入失败
请求 B 到来 → Quota 查询数据库 → 看不到 A → 错误放行
```

**"数据库查询失败时拒绝"不能解决这个问题** —— 数据库是健康的，只是历史记录丢了。

**要让 Quota 可靠，必须**：

1. 设计独立的额度累计持久化，与 Usage 写入解耦
2. 明确结算与额度更新的事务关系
3. 处理崩溃恢复时的额度一致性

**V0.4.1 的前提**：

- 明确额度累计的权威来源
- 明确写入失败对额度的影响
- 明确缓存与持久化的一致性

**如果 V0.4.1 的实现仍无法保证"额度累计不会因写入失败而低估"**，则应在文档中明确 Quota 是"尽力而为"而非"强一致"，不做超出实现能力的承诺。

---

### 决策 6：月度窗口使用 UTC 自然月，左闭右开区间

（保留用于 V0.4.1 及查询聚合的时间窗口计算）

- 时区：**UTC**
- 窗口边界：`[monthStart, nextMonthStart)` —— 左闭右开
- 时间存储：**UTC epoch millis**（`long`），不混用本地时间
- 每月第一天 00:00:00 UTC 开始新窗口

**理由**：

- 自然月对用户直觉友好（"这个月我用了多少"）
- UTC 避免时区混乱
- 左闭右开避免边界请求被重复计入两个月

---

### 决策 7：Virtual Key 的内部 ID 与认证秘密分离

**模型**：

```java
public class VirtualKey {
    String id;              // 内部稳定标识
    String name;            // 展示名
    String key;             // 认证秘密（客户端使用）
    RateLimitPolicy rateLimit;
}
```

**配置示例**：

```yaml
mixinfer:
  virtual-keys:
    - id: vk_team_a
      name: "Team A"
      key: sk-mixinfer-a
      rate-limit:
        requests-per-minute: 60
```

**为什么不把 `key` 当主键**：

- 用户可能轮换 key（安全原因）
- 用户可能改 `name`（展示原因）
- 历史 UsageRecord 需要稳定的关联

**兼容旧配置的策略**：

V0.1–V0.3 的 `api-keys: [{key, name}]` 继续支持，但**不直接以 `name` 作为 `id`**。

**规则**：

- **新格式**：**必须**显式指定 `id`。改名、轮换 key，`id` 都不变
- **旧格式**：以规范化后的 `name`（如 trim + 小写）确定性生成 ID（例如基于 name 的稳定哈希）
- **改名语义**：旧格式的 `id` 与 `name` 绑定。**修改 `name` 视为新身份**，历史 UsageRecord 将不再关联到该 Key
- **不做**运行时改名检测 —— 这需要保存历史配置，代价过高，超出 V0.4 的范围

**文档明确**："旧格式的 ID 与 name 绑定；如需改名并保留历史关联，请迁移到新格式并显式指定 `id`。"

**Key 轮换**：`id` 保持不变，`key` 可变更。

**为什么不引入自动改名检测**：需要持久化配置历史、比对旧值、处理多环境差异。这是配置管理系统的工作，不是 V0.4 的网关应承担的职责。

---

### 决策 8：Admin API 使用独立认证，不复用客户端 Key

**新增独立配置**：

```yaml
mixinfer:
  admin:
    api-key: ${MIXINFER_ADMIN_KEY}
```

**`ApiKeyFilter` 扩展**：

- `/v1/**` 走客户端 Virtual Key
- `/admin/**` 走独立的 admin key
- 客户端 key 访问 `/admin/**` 返回 401
- admin key 访问 `/v1/**` 也返回 401（权限分离）

**V0.4 不实现 RBAC** —— 只有一个 admin key，无角色。

**响应不返回明文 key**：

- 查询结果以 `virtualKeyId` 或 `name` 显示
- 不提供返回明文 key 的能力

---

### 决策 9：Usage 查询 API 分离明细与聚合

**明细查询**：

```text
GET /admin/usage/records
  ?requestId=
  &from=
  &to=
  &model=
  &provider=
  &endpointId=
  &virtualKeyId=
  &success=
  &settlementStatus=
  &limit=
  &offset=
```

返回单条记录的列表，支持按多个条件过滤。用于"追查某次请求发生了什么"。

**聚合查询**：

```text
GET /admin/usage/aggregate
  ?from=
  &to=
  &groupBy=model|provider|endpointId|virtualKeyId
  &window=hour|day|month
  &model=         (optional filter)
  &provider=      (optional filter)
  &virtualKeyId=  (optional filter)
```

返回聚合结果：请求数、成功请求数、输入 / 输出 token、成本。

**两个端点，语义清晰**：

- 明细是"记录列表"
- 聚合是"统计结果"

**不合并为一个端点** —— 虽然技术上可以做，但两种查询的返回结构、分页方式、性能特征完全不同。

**统计口径的明确**：

- **请求数**：按 `requestId` 去重，**包含**被拒请求
- **成功请求数**：`success = true`
- **上游尝试次数**：从 `attemptCount` 字段求和，**不参与客户端用量**
- 聚合结果**明确区分**"总请求数"和"成功请求数"

---

## Consequences

### Positive

- **可查询的用量**：从"能看日志"升级为"能查、能聚合、能跨重启"
- **成本可见**：每次请求结算出 cost，支持按 model / provider / endpoint / Key / 时间聚合
- **治理基础**：限流是最小可用的准入控制
- **单实例可部署**：不引入 Redis / PostgreSQL / MQ，保持"可嵌入"的定位
- **面试叙事强**：准入 vs 结算、尽力而为 vs 强一致、故障降级 vs 保守拒绝 —— 都是高级话题
- **诚实的边界**：明确不做的比明确做了的更重要

### Negative

- **SQLite 写性能上限**：单写事务，高并发场景会成为瓶颈。V0.4 接受
- **无 Quota**：V0.4 不提供额度控制。V0.4.1 才有
- **准入查询失败会拒绝请求**：可用性受影响。这是有意的取舍
- **Admin 认证是单 key**：无细粒度权限。V0.5 引入 RBAC 时解决

### Neutral

- `usage.log` 保留为历史文件
- `VirtualKey` 是 `ApiKeyConfig` 的超集，旧配置继续有效
- `attempts` 字段是诊断扩展点

---

## Alternatives Considered

### Alternative 1：使用 PostgreSQL 而不是 SQLite

- **优点**：成熟的并发能力，可扩展为多实例
- **拒绝理由**：V0.4 明确不引入独立数据库服务。SQLite 的单实例能力足够

### Alternative 2：使用 Redis 做 Rate Limit

- **优点**：分布式原生支持，多实例一致
- **拒绝理由**：V0.4 是单实例。引入 Redis 会破坏"可嵌入"的定位，且增加部署复杂度

### Alternative 3：V0.4 同时实现 Quota

- **优点**：功能更完整
- **拒绝理由**：V0.4 的 Usage 写入是尽力而为，无法作为 Quota 的权威依据。强行实现会得到"接口看起来有配额控制、实际可能因历史记录丢失而错误放行"的危险状态

### Alternative 4：Cost 按逻辑 model 定价

- **优点**：配置更简单
- **拒绝理由**：同一个逻辑 model 可能路由到不同计价的上游，会产生错误成本

### Alternative 5：数据库故障时降级放行

- **优点**：可用性更高
- **拒绝理由**：让限流形同虚设，且掩盖故障。**宁可拒绝也不可静默绕过**

### Alternative 6：Admin API 复用客户端 Key

- **优点**：不新增配置
- **拒绝理由**：客户端 key 不应获得查看全局用量的权限，即使 V0.4 不做 RBAC

### Alternative 7：继续使用 JSON Lines，不引入 SQLite

- **优点**：零新增依赖
- **拒绝理由**：无法聚合、无法跨重启可信查询

### Alternative 8：被拒请求也计入滑动窗口

- **优点**：能限制"反复触发 429 的客户端"
- **拒绝理由**：持续重试的客户端会不断刷新窗口，永远处于被限流状态。防滥用是独立问题，应该作为独立机制设计

---

## References

- [SQLite WAL mode](https://www.sqlite.org/wal.html)
- [SQLite busy_timeout](https://www.sqlite.org/pragma.html#pragma_busy_timeout)
- [OpenAI Error Codes](https://platform.openai.com/docs/guides/error-codes)
- [HTTP Retry-After](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Retry-After)
- [ADR-003](./003-multi-endpoint-routing.md)