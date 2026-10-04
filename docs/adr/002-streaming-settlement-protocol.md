# ADR-002: Streaming Proxy and Usage Settlement Protocol

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

V0.1 使用 Spring `RestClient`（阻塞式），它在流式场景下有两个限制：

- **它不支持"逐块消费响应体"** —— 它一次性把响应体读完
- 虽然可以拿 `InputStream`，但 `RestClient` 的抽象层会打断这个流

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

### 决策 1：使用原生 `java.net.http.HttpClient`

**理由**：

- **零新依赖**：JDK 17 内置，不引入 WebFlux 或 Apache HttpClient
- **支持流式**：`HttpResponse.BodyHandlers.ofLines()` 直接返回 `Stream<String>`
- **不需要响应式栈**：V0.1 是阻塞式 MVC，引入 WebFlux 会造成两套栈并存
- **面试可讲**：原生 API 的使用体现基本功，比调框架更有说服力

**代价**：

- API 比 `RestClient` 繁琐：手动构造 `HttpRequest`、手动加 headers
- 需要自己处理连接池（`HttpClient` 自带连接池，配置即可）

**实施**：`OpenAICompatibleProvider` 保留 `RestClient` 用于非流式，
新增 `HttpClient` 字段用于流式。**两者并存，职责清晰。**

### 决策 2：新增 `StreamingLlmProvider` 接口

```java
public interface StreamingLlmProvider {
    Stream<LlmStreamChunk> invokeStream(LlmRequest request, Endpoint endpoint);
}
```

`OpenAICompatibleProvider` 同时实现 `LlmProvider` 和 `StreamingLlmProvider`。

**理由**：

- **接口隔离原则**：不支持流式的 Provider 不必实现
- **V0.1 接口不变**：不破坏现有代码
- **服务层做能力判断**：请求 `stream: true` 时，如果当前 Provider
  未实现 `StreamingLlmProvider`，返回 400 错误（明确的、可诊断的失败）

**拒绝的方案 A（直接加方法）**：
所有 Provider 被迫实现流式，不符合现实——有些自建 Provider
（如内部 RPC 协议）确实不支持流式。

**拒绝的方案 C（统一入口）**：
`invoke` 返回类型用泛型或联合类型，会让服务层充满 `instanceof`
和 cast，可读性差。

### 决策 3：新增 `LlmStreamChunk` 类型

```java
@Value
@Builder
public class LlmStreamChunk {
    String id;
    String model;
    int index;
    LlmMessageDelta delta;         // 增量内容
    String finishReason;           // 仅最后一块有
    LlmUsage usage;                // 仅最后一块有（如果上游支持）
}
```

配套：

```java
@Value
@Builder
public class LlmMessageDelta {
    String role;                   // 仅第一块有
    List<ContentPart> content;     // 内容增量
}
```

**设计要点**：

- **不复用 `LlmResponse`**：语义不同——一个是完整的，一个是增量的
- **`finishReason` 和 `usage` 可为 null**：只有最后一块才有
- **`content` 仍然用 `List<ContentPart>`**：与非流式的 IR 一致，
  为多模态预留

### 决策 4：流式用量采用"三段式结算"

**第一段：正常结束**

- 上游返回 `data: [DONE]`
- MixInfer 记录 `UsageRecord`（success=true），usage 来自最后一个 chunk

**第二段：客户端中途断开**

- Servlet 检测到 `IOException`（客户端关闭连接）
- MixInfer **立即停止**读取上游流
- 记录 `UsageRecord`（success=false，reason=client_disconnected），
  usage 为 null 或已知的累计值

**第三段：上游中途断开**

- 上游的 `Stream<String>` 抛异常或提前结束
- MixInfer 向客户端发一个错误事件：`data: {"error":{...}}\n\n`
- 然后关闭连接
- 记录 `UsageRecord`（success=false，reason=upstream_disconnected）

**关键约束**：**只有一份连接，只有一个响应流。**
MixInfer 不做任何缓冲，不做任何预读，不做任何"部分回放"。

### 决策 5：Controller 使用手动 `HttpServletResponse`

不用 `SseEmitter`，不用 `Flux<ServerSentEvent>`。

**理由**：

- `SseEmitter` 是 Spring 的异步抽象，对超时和断开的控制粒度不够细
- `Flux<ServerSentEvent>` 需要引入 WebFlux 依赖
- **手动 `HttpServletResponse` 是最可控的方式**：
    - 明确控制 `Content-Type: text/event-stream`
    - 明确控制 `Cache-Control: no-cache`
    - 明确控制每块 `flush()` 的时机
    - 明确捕获 `IOException` 处理客户端断开

**代价**：需要手写 SSE 格式（`data: {...}\n\n`）。

**可接受**：格式简单，且这块代码未来是核心面试讲点。

## Consequences

### Positive

- **零新框架**：不引入 WebFlux，V0.1 的 MVC 栈不变
- **接口清晰**：`StreamingLlmProvider` 是独立能力声明，不是
  `LlmProvider` 的负担
- **结算协议明确**：三段式场景覆盖了流式的所有中断情况
- **面试叙事强**：SSE 解析、连接管理、中断处理，都是可讲点

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