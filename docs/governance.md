# MixInfer 治理层设计

本文档描述 V0.4 引入的用量、成本与限流能力。
面向想理解治理边界与取舍的读者，与 [ADR-004](adr/004-usage-cost-rate-limit-quota.md) 互补。

## 1. 治理在请求生命周期中的位置

```
认证 → 准入（Rate Limit）→ 路由 → 调用 → 结算 → 持久化 → 查询
        ↑ 请求前                              ↑ 请求后
   基于已有状态判断                       记录本次结果
```

**核心区分**：**准入判断发生在请求前**，**实际用量在请求后确定**。
两类操作在故障语义上必须分开处理——见 [故障语义](#5-故障语义)。

## 2. 用量记录

### 2.1 统计单位

**一次客户端请求对应一条 UsageRecord**。即使请求经过多次 Failover 尝试，也**仍是一条**。

- 上游尝试次数记录在 `attemptCount` / `attempts`
- `attemptCount` 参与聚合统计（`SUM(attempt_count)`）
- `attempts` 是诊断明细（JSON），**不参与业务统计**

### 2.2 记录时机

| 场景 | 是否记录 | success | reason |
|---|---|---|---|
| 认证失败（未知 Key） | 否——主体未知 | — | — |
| Rate Limit 拒绝 | 是 | false | `rate_limited` |
| 路由失败 | 是 | false | `routing_failed` |
| 全部 Endpoint 失败 | 是 | false | `all_endpoints_failed` |
| 客户端断开（流式） | 是 | false | `client_disconnected` |
| 上游中途断流 | 是 | false | `upstream_stream_error` |
| 正常完成 | 是 | true | — |

## 3. 成本计算

### 3.1 定价维度

**按 (Endpoint, upstream model) 定价**，不是按逻辑 model。

原因：同一个逻辑 model 可能路由到不同计价的上游。

### 3.2 精度

- 内部表示：`long`，单位 **microcents**（1 cent = 1,000,000 microcents）
- 币种：固定 USD
- **禁止 `double` 中间值**：用 `long` 乘法 + 整数除法，四舍五入到 microcents
- 溢出保护：`Math.multiplyExact` / `Math.addExact`，溢出时返回 `null` 而非错误值

### 3.3 未知用量的语义

| 场景 | costMicrocents |
|---|---|
| 上游返回完整 usage 且有定价 | 计算值 |
| 上游返回部分 usage 且有定价 | 基于已知部分计算 |
| 上游未返回 usage | `null` |
| 该 (endpoint, upstreamModel) 无定价配置 | `null` |

**关键原则**：**未知成本绝不记 0**——否则聚合报表会低估成本。

**识别"成功但无成本"的请求**：`success = true && costMicrocents = null`。

### 3.4 语义边界

`costMicrocents` 是**按配置单价估算的成本**，**不是供应商实际账单金额**。
供应商可能有缓存折扣、阶梯价或其他调整。字段名或文档中需明确。

## 4. 限流

### 4.1 滑动窗口

时间窗口：`(now - windowSize, now]`，**左开右闭**。

同一 Key 的准入判断 + 记录时间戳在**同一个临界区**完成。

### 4.2 被拒请求不进入窗口

**被拒绝的请求不写入窗口时间戳。**

原因：如果被拒请求也计数，持续重试的客户端会不断刷新窗口，
永远处于被限流状态——即使最早的正常请求已经过期。

**防滥用是独立问题**：如需要限制"反复触发 429 的客户端"，
应作为独立机制设计，不混入正常业务限流。

### 4.3 Retry-After

从**最早的有效准入时间戳**计算剩余窗口时间，向上取整到秒，**至少为 1 秒**。

### 4.4 并发安全

所有 per-key 状态操作通过 `ConcurrentHashMap.compute` 完成——
准入、清理、bucket 生命周期在同一个 Key 上互斥。

## 5. 故障语义

### 5.1 两类失败

| 失败类型 | 处理 |
|---|---|
| **Usage 写入失败**（磁盘满、DB 锁定） | 记录 warn，**不影响客户端请求返回** |
| **准入查询失败**（Rate Limit 状态异常） | **拒绝新请求**，返回 503 |

**理由**：

- 写入失败是"事实丢失"——客户端已拿到结果，强行 500 反而更差
- 准入失败是"潜在滥用"——放行会让限流形同虚设

### 5.2 数据库故障

- **使用中**：数据库不可写 → Usage 记录丢失，但请求正常
- **准入**：数据库不可读（V0.4.1 Quota 场景）→ **拒绝新请求，返回 503**

### 5.3 与 V0.4.1 Quota 的关系

V0.4 的 Usage 写入是**尽力而为**。**不能**直接依赖这些记录
作为 Quota 的权威依据——见 ADR-004 决策 5。

## 6. Admin API

### 6.1 独立认证

- `/v1/**` 走客户端 Virtual Key
- `/admin/**` 走独立的 admin key
- **客户端 key 访问 `/admin/**` 返回 401**
- **admin key 访问 `/v1/**` 返回 401**（权限分离）

### 6.2 端点

| 端点 | 用途 |
|---|---|
| `GET /admin/usage/records` | 明细查询（按 requestId / 时间范围 / model / provider / endpointId / virtualKeyId / success / settlementStatus 过滤） |
| `GET /admin/usage/aggregate` | 聚合查询（按 model / provider / endpointId / virtualKeyId 分组，可选 hour / day / month 窗口） |

### 6.3 响应不含明文 Key

查询结果以 `virtualKeyId` 或 `name` 显示。**不返回** `sk-xxx` 形式的秘密。

## 7. 存储

### 7.1 SQLite 取舍

- **单写连接**（HikariCP `maximumPoolSize = 1`）——以简单性换并发能力
- WAL 模式——降低读事务阻塞写的概率；配合单连接池收益有限
- `busy_timeout = 5000`——锁等待 5 秒

### 7.2 版本化 Migration

使用 `V1__init.sql` / `V2__...` 形式的 migration，应用启动时执行。
**不用** `CREATE TABLE IF NOT EXISTS`——字段演进时会静默失败。

### 7.3 数据路径

配置：`mixinfer.storage.sqlite.path`，默认 `logs/mixinfer.db`。
**Docker 必须挂载 volume**——否则容器重建会丢失数据。

## 8. V0.4 不做的内容

- **Quota / Budget**——顺延到 V0.4.1（前提：额度累计的可靠性有明确方案）
- **多租户 / RBAC**——V0.5+
- **分布式限流**（Redis）——V0.4 是单实例
- **PostgreSQL / MQ**——继续 SQLite
- **控制平面 / Web UI**——V0.5+
- **严格配额**（预留 + 释放机制）——超出 V0.4 复杂度预算