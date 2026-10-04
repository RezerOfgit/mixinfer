# ADR-002: Streaming Data Plane and Usage Settlement

- **Status**: Proposed
- **Date**: 2026-10-04
- **Deciders**: MixInfer Author
- **Related**: [ADR-001](./001-use-semantic-ir.md), [architecture.md](../architecture.md)

## Context

V0.1 只支持非流式请求：客户端发送一个 JSON，MixInfer 等上游返回
完整响应，再转换回客户端。整个链路是"一问一答"。

V0.2 必须支持流式（SSE）。客户端发送 `"stream": true` 时：

1. MixInfer 必须向上游发起流式请求
2. 上游逐块返回 `data: {...}\n\n`，每块是一个增量 delta
3. MixInfer 必须**边收边转边发**，不能缓冲整个响应
4. 客户端能"逐字看到"输出，而不是等 5 秒后一次性收到

这带来四个新的设计问题：

### 问题 1：HTTP 客户端选型

V0.1 使用 Spring `RestClient`（阻塞式），其使用方式是面向完整响应体的同步调用。
V0.2 需要显式控制上游流的生命周期——这是当前 `RestClient` 用法未覆盖的场景，
因此需要选一个能逐块消费响应体的 HTTP 客户端。

三个候选方案：

| 方案 | 优点 | 缺点 |
|---|---|---|
| Spring WebClient | 响应式，SSE 支持成熟 | 引入 WebFlux，与 V0.1 的 MVC 栈并存 |
| 原生 `java.net.http.HttpClient` | 零新依赖，JDK 内置，支持流式 | API 略繁琐 |
| Apache HttpClient 5 | 成熟，功能全 | 多一个第三方依赖 |

### 问题 2：Provider 接口的扩展方式

V0.1 的 `LlmProvider`：

```java
public interface LlmProvider {
    String name();
    LlmResponse invoke(LlmRequest request, Endpoint endpoint);
}
```

V0.2 需要加流式方法。有三种方式：

| 方式 | 说明 | 取舍 |
|---|---|---|
| A：直接加 `invokeStream` | `LlmProvider` 加一个方法 | 所有实现都必须支持流式，强约束 |
| B：新增 `StreamingLlmProvider` 接口 | `OpenAICompatibleProvider` 同时实现两个接口 | 接口隔离，不强制所有 Provider 支持流式 |
| C：`invoke` 返回 `LlmResult`，内部区分 | 统一入口，运行时判断 | 接口语义模糊，调用方复杂 |

### 问题 3：IR 的流式表达

非流式的 `LlmResponse` 是一个完整对象：

```java
class LlmResponse {
    String id;
    String model;
    List<LlmChoice> choices;
    LlmUsage usage;
}
```

流式的每个块只是一个"增量"：

```jsonl
{"id":"chatcmpl-xxx","choices":[{"index":0,"delta":{"content":"Hello"}}]}
{"id":"chatcmpl-xxx","choices":[{"index":0,"delta":{"content":" world"}}]}
{"id":"chatcmpl-xxx","choices":[{"index":0,"finish_reason":"stop"}]}
```

**关键差异**：`delta` 而不是 `message`，`finish_reason` 与 content 分块到达。

这需要新的 IR 类型，不能复用 `LlmResponse`。

### 问题 4：流式场景下的 Usage 结算

这是最复杂的一点。

**非流式场景**：上游返回的 `usage` 字段完整，直接读。

**流式场景**：OpenAI 的流式响应**默认不返回 usage**。必须显式请求：

```json
{
  "model": "gpt-4o-mini",
  "stream": true,
  "stream_options": {"include_usage": true}
}
```

加上这个之后，最后一个 chunk 会带 `usage`：

```json
{"id":"...","choices":[],"usage":{"prompt_tokens":31,"completion_tokens":39,"total_tokens":70}}
```

**但不是所有 OpenAI 兼容上游都支持 `stream_options`。**
DeepSeek、通义、vLLM 支持情况不一。

而且有三种中断场景必须处理：

1. **正常结束**：收到 `data: [DONE]`，usage 已记录
2. **客户端中途断开**：MixInfer 必须停止向上游读取，并记录"未完成"事件
3. **上游中途断开**：MixInfer 必须向客户端发一个错误事件，并记录失败

## Decision

### 决策 1：V0.2 将流式作为一等数据平面能力

V0.2 引入 SSE 作为正式支持的工作模式，而非事后追加的功能。
上游调用与下游响应两条路径都原生支持流式。

### 决策 2：新增 `StreamingLlmProvider` SPI 与 `LlmStream` 抽象

```java
public interface StreamingLlmProvider {

  LlmStream invokeStream(LlmRequest request, Endpoint endpoint);
}

public interface LlmStream extends AutoCloseable {

  /**
   * Returns the next chunk, or null if the stream is exhausted.
   * Blocks until a chunk is available.
   */
  LlmStreamChunk next() throws IOException;

  /**
   * Cancels the stream and releases upstream resources.
   * Must be idempotent. After close(), next() returns null.
   */
  @Override
  void close();
}
```

**为什么不用 `Stream<LlmStreamChunk>`**：Java `Stream<T>` 建模的是有限惰性集合，
缺少资源释放、可取消性、IO 异常三类语义。LLM 流式是长生命周期、可中断的 IO 流，
必须显式建模。对流式而言，`hasNext()` + `next()` 两次调用是冗余的——
`next()` 返回 null 表示结束，语义更简单。

**接口隔离**：不支持流式的 Provider 不必实现 `StreamingLlmProvider`。
服务层在收到 `stream: true` 但当前 Provider 不支持时，返回明确的 400 错误。

### 决策 3：各 Provider 的流式格式统一映射到 Provider 无关的 `LlmStreamChunk` IR

`LlmStreamChunk` 是语义级 IR 类型，不是对 OpenAI chunk 的重命名。
各 Provider 通过自己的 Stream Adapter 映射到统一 IR：

```
HTTP Byte Stream → SSE Framing → data field → JSON → Provider DTO → LlmStreamChunk
```

未来新增 Provider 无需改动 IR：

```
OpenAI SSE    → OpenAI Stream Adapter    → LlmStreamChunk
Anthropic SSE → Anthropic Stream Adapter → LlmStreamChunk
```

解析分层：`SseLineReader`（SSE 帧解析）→ `OpenAIStreamParser`（JSON → DTO → IR）。
**Controller 不做 JSON 解析**，只负责 HTTP in → Service → stream out。

```java
@Value
@Builder
public class LlmStreamChunk {
    String id;
    String model;
    int index;
    LlmMessageDelta delta;   // 增量内容
    String finishReason;     // 仅最后一块有
    LlmUsage usage;          // 仅最后一块有（如果上游返回）
}

@Value
@Builder
public class LlmMessageDelta {
    String role;                // 仅第一块有
    List<ContentPart> content;  // 内容增量
}
```

`content` 保持 `List<ContentPart>`，与非流式 IR 一致，为多模态预留。

### 决策 4：流式上游使用 JDK `HttpClient`，非流式保留 Spring MVC 与 `RestClient`

**理由**：

- **零新依赖**：JDK 17 内置，不引入 WebFlux 或 Apache HttpClient
- **支持流式**：使用 `HttpResponse.BodyHandlers.ofInputStream()` 拿原始字节流，
  自行按 SSE 帧边界（`\n\n`）解析，而不是 `BodyHandlers.ofLines()`
  - SSE 是字节流，不是行流
  - 不受默认字符集影响（SSE 强制 UTF-8）
  - 能正确处理 `\r\n` 与 `\n` 两种换行
- **不引入响应式栈**：V0.1 是阻塞式 MVC，引入 WebFlux 会造成两套栈并存
- **抽象开销低**：MixInfer 与上游流之间没有框架层间接

**代价**：

- API 比 `RestClient` 繁琐：需要手动构造 `HttpRequest`、手动加 headers
- JDK `HttpClient` 自动管理连接复用/池化，但配置模型不如 Apache HttpClient 丰富。
  需重点考虑：connect timeout、request timeout、stream lifetime、executor、connection reuse

**实施**：`OpenAICompatibleProvider` 保留 `RestClient` 处理非流式；
新增 `OpenAICompatibleStreamingProvider` 使用 `HttpClient` 处理流式。
两个类，两个职责。


### 决策 5：下游断开时，必须取消上游流（MUST）

这是一项 **MUST** 要求，不是优化。

```
Client disconnect → LlmStream.close() → Cancel upstream → Release connection → Settlement
```

```java
try (LlmStream stream = provider.invokeStream(request, endpoint)) {
    LlmStreamChunk chunk;
    while ((chunk = stream.next()) != null) {
        try {
            writeToClient(response, chunk);
        } catch (IOException e) {
            // Client disconnected -> MUST cancel upstream
            stream.close();
            throw e;
        }
    }
}
```

**若不实现此点**，客户端断开后 MixInfer 会继续消耗上游 token 而无接收方。
这是网关的一等关注点，其价值不低于"能够输出 SSE"。

### 决策 6：用量结算是显式状态机

流式模式下，不能假设流结束时一定有 usage。某些上游不返回 usage，
而客户端断开与上游断开都会让真实用量无法确定。结算模型必须诚实反映这一点。

```java
public enum UsageSettlementStatus {
    PENDING,    // 流进行中，usage 尚未确定
    FINAL,      // 上游返回完整 usage
    PARTIAL,    // 仅拿到部分 usage
    UNKNOWN     // usage 无法确定
}

public enum UsageSource {
    PROVIDER,   // V0.2 实现
    ESTIMATED,  // V0.3 预留（如本地 tokenizer）
    NONE        // V0.2 实现
}
```

概念模型：

```
UsageRecord
 ├── providerReported     // 上游返回的 LlmUsage（可能为 null）
 ├── estimated            // 自行累计/估算的 usage（可选）
 ├── settlementStatus     // UsageSettlementStatus
 └── reason               // 中断原因（正常结束为 null）
```

**核心原则：客户端断开 ≠ 上游停止计费。** 客户端断开后 MixInfer 无法知道
上游实际生成了多少 token。诚实记录 `UNKNOWN`，比编造一个数字更专业。

四种结算场景：

| 场景 | settlementStatus | usageSource | reason |
|---|---|---|---|
| 正常结束（有 usage） | FINAL | PROVIDER | — |
| 正常结束（无 usage） | UNKNOWN | NONE | — |
| 客户端断开 | PARTIAL / UNKNOWN | NONE | client_disconnected |
| 上游断开 | PARTIAL / UNKNOWN | PROVIDER 或 NONE | upstream_disconnected |

### 决策 7：Controller 使用手动 `HttpServletResponse`

`SseEmitter` **能够**实现 SSE；V0.2 选择 `HttpServletResponse`
是为了**显式控制**下游响应与上游流之间的生命周期关系，而非因为 `SseEmitter`
能力不足。

具体控制点：

- 显式设置 `Content-Type: text/event-stream`
- 显式设置 `Cache-Control: no-cache`
- 每块 `flush()` 的时机由 MixInfer 控制
- 客户端断开通过写操作的 `IOException` 检测，并触发上游取消（决策 5）

拒绝 `Flux<ServerSentEvent>` 是因为会引入 WebFlux 依赖。
代价是手动格式化 SSE（`data: {...}\n\n`），可接受——格式简单，
且这块代码是核心技术点。

## Consequences

### Positive

- **零新框架**：不引入 WebFlux，V0.1 的 MVC 栈不变
- **接口清晰**：`StreamingLlmProvider` 是独立能力声明，不是
  `LlmProvider` 的负担
- **结算协议明确**：三段式场景覆盖了流式的所有中断情况
- **可诊断性**：SSE 解析、连接管理、中断处理都有明确的错误语义

### Negative

- **手动管理 HTTP 连接**：需要理解 `HttpClient` 的连接池和超时
- **代码量增加**：SSE 解析、分块转发、错误处理都需要自己写
- **测试复杂度提高**：流式测试比同步测试难写

### Neutral

- V0.2 后 Provider 需要实现两个方法（`invoke` + `invokeStream`），
  这是合理的——流式和非流式本质是两种调用模式

## Alternatives Considered

### Alternative 1: 引入 WebFlux，统一响应式栈

- **优点**：SSE 原生支持，`Flux<ServerSentEvent>` 开箱可用
- **拒绝理由**：
    - V0.1 是阻塞式 MVC，引入 WebFlux 会造成两套栈并存
    - 个人项目引入全栈响应式，会稀释"简单清晰"的架构叙事
    - 响应式编程对面试官来说也是加分项，但**如果只是调框架**，
      反而会被追问"为什么用 WebFlux"

### Alternative 2: 用 `SseEmitter` 做流式

- **优点**：Spring 官方推荐，与 MVC 兼容
- **拒绝理由**：`SseEmitter` 的底层仍然是 `HttpServletResponse`，
  但它的抽象会隐藏"什么时候 flush"、"客户端断开怎么处理"这些细节。
  V0.2 想要的是**完全的控制**，不是便利。

### Alternative 3: 不做流式，V0.2 只做 Failover

- **优点**：工作量减半，V0.2 能更快发布
- **拒绝理由**：
    - 流式是 LLM 应用的刚需，没有流式的网关会被用户立刻抛弃
    - 流式是面试中最能体现"深入理解 HTTP"的技术点
    - 推迟到 V0.3 会让 V0.2 显得平淡

## References

- [OpenAI Streaming API](https://platform.openai.com/docs/api-reference/chat/streaming)
- [Server-Sent Events Specification](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [Java 17 HttpClient](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html)
- [Spring Framework: SSE](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)