# Streaming in MixInfer

本文档描述 V0.2 流式数据平面的实现细节。
面向想理解内部机制的读者，与 [ADR-002](adr/002-streaming-data-plane.md) 互补。

## 1. 数据流

```
Upstream SSE bytes
       ↓
SseEventParser            — 拆出每个事件的 data 字段
       ↓
OpenAIStreamChunk         — Jackson 解析成 DTO
       ↓
OpenAIStreamConverter     — 转成 IR
       ↓
LlmStreamChunk            — Provider 无关的增量
       ↓
LlmToOpenAIStreamConverter — 序列化成 OpenAI SSE JSON
       ↓
StreamingResponseWriter   — 加上 SSE 帧前缀
       ↓
Client
```

## 2. 关键类

| 类 | 职责 |
|---|---|
| `SseEventParser` | 从 `InputStream` 解析 SSE 帧，只暴露 `data` 字段 |
| `OpenAIStreamChunk` | OpenAI 流式 chunk 的 DTO |
| `OpenAIStreamConverter` | OpenAI chunk → `LlmStreamChunk` |
| `LlmStreamChunk` | Provider 无关的流式 IR |
| `LlmToOpenAIStreamConverter` | `LlmStreamChunk` → OpenAI 兼容 JSON 字符串 |
| `StreamingResponseWriter` | 写 SSE 帧到 `HttpServletResponse` |
| `OpenAICompatibleStreamingProvider` | 用 JDK `HttpClient` 打开上游 SSE 连接 |
| `HttpLlmStream` | `LlmStream` 的 HTTP SSE 实现 |

## 3. 关键陷阱

### 3.1 PrintWriter 会吞掉 IOException

`HttpServletResponse.getWriter()` 返回的 `java.io.PrintWriter`
**会静默吞掉 IOException**，只设置一个内部错误标志（`checkError()` 查询）。
客户端断开时，`writer.flush()` 不会抛异常，取消上游的逻辑永远不会触发。

**解决**：用 `response.getOutputStream()`（`ServletOutputStream`）。
它的 `flush()` 会真正抛 `IOException`。

### 3.2 SSE 是字节流，不是行流

不要用 `HttpResponse.BodyHandlers.ofLines()`。

- SSE 规范不保证换行统一（`\n` 和 `\r\n` 都合法）
- 行读抽象会隐藏分帧边界
- 用 `BodyHandlers.ofInputStream()` + 自行按空行切帧，最可控

### 3.3 `try-with-resources` 不够

```java
try (LlmStream stream = provider.invokeStream(...)) {
    ...
}
```

问题：`try-with-resources` 里若同时有业务异常和 close 异常，close 异常会被压制；
且它无法区分"关闭前是否已经写过头"。

**解决**：显式 `try/catch/finally`，在 finally 里 `stream.close()`，
并根据 `responseCommitted` 标志决定是发 error 事件还是抛异常。

## 4. 生命周期保证

| 事件 | 行为 |
|---|---|
| 客户端断开 | `stream.close()` 关闭上游连接；结算 UNKNOWN + client_disconnected |
| 上游中途断流（SSE 头已发） | 发 SSE error 事件；结算 PARTIAL/UNKNOWN + upstream_stream_error |
| 上游中途断流（SSE 头未发） | 正常抛异常；GlobalExceptionHandler 返回 HTTP 错误 |
| 正常结束 | 写 `[DONE]`；结算 FINAL（有 usage）或 UNKNOWN（无 usage） |

## 5. 未实现（V0.3+）

- **Heartbeat**：长连接保活（`: ping\n\n`）。当前依赖上游每隔几秒就会发 chunk；如果上游静默超过 Nginx / LB 的空闲超时，连接会被切断。V0.3 加。
- **流式重试**：流式请求重试比非流式复杂（部分响应已经发给客户端）。V0.3 加。
- **多 Endpoint 流式 fallback**：只能在"打开上游"阶段 fallback，不能中途切换。V0.3 设计。