# ADR-001: Use Semantic Internal Representation (IR) Instead of Protocol-Level Conversion

- **Status**: Accepted
- **Date**: 2026-10-01
- **Deciders**: MixInfer Author
- **Related**: [architecture.md](../architecture.md), [ir-design.md](../ir-design.md)

## Context

MixInfer 需要支持多个 LLM Provider（OpenAI、Anthropic、Gemini 等）。
这些 Provider 的 API 格式、字段命名、能力集合各不相同。

在 V0.1 阶段，我们面临一个关键的架构选择：

**如何在不同 Provider 之间实现请求/响应的统一？**

业界现有两种主流做法：

1. **协议级转换（Protocol-Level Conversion）**
    - 客户端发 OpenAI 格式 → 直接翻译成目标 Provider 格式 → 转发
    - 代表项目：One-API、New-API、APISIX AI Gateway
    - 特点：所有 Provider 都以 OpenAI 格式为"中间格式"

2. **语义级内部表示（Semantic Internal Representation）**
    - 客户端格式 → 转换为**语义无关的内部模型** → 再由 Provider 适配器转换为目标格式
    - 代表项目：LiteLLM（部分）、Bifrost（部分）
    - 特点：IR 独立于任何 Provider，描述"语义"而非"格式"

## Decision

**V0.1 采用语义级 IR（Semantic IR）方案。**

核心设计：

- 定义 `LlmRequest` / `LlmResponse` 作为**内部统一模型**
- IR 描述**语义**（messages、tools、reasoning），不绑定任何 Provider 格式
- 客户端格式 → IR → Provider 格式，单向转换链
- Provider 特有能力通过 `extensions` 字段透传，不污染 IR

具体字段设计见 [ir-design.md](../ir-design.md)。

## Rationale

### 为什么不用协议级转换？

**理由 1：协议级转换会导致"格式泄漏"。**

如果以 OpenAI 格式作为中间格式，那么 OpenAI 的字段设计就会渗透到整个系统。
当需要支持 Anthropic 时，会发现 Anthropic 的 `system` 字段、`tool_use` 结构、
`stop_reason` 语义都无法用 OpenAI 格式精确表达。

**理由 2：协议级转换的"降级"能力弱。**

当目标 Provider 不支持某个能力（比如 Function Calling）时，协议级转换只能报错
或者静默丢弃。语义级 IR 可以声明"该能力不支持"并在转换层做降级（如 Prompt 注入）。

**理由 3：IR 是长期资产，协议是短期接口。**

OpenAI 格式会变（已经从 Completion API 演进到 Chat API，再到 Responses API）。
如果系统内部绑定 OpenAI 格式，每次 OpenAI 改格式，系统都要改。
IR 一旦设计稳定，可以独立于任何 Provider 的格式演进而保持稳定。

### 为什么不用更激进的方案（完全自定义 IR）？

**理由：V0.1 需要保持最小可运行。**

完全自定义 IR（比如不使用 messages 数组，而使用 graph-based 语义表示）
在理论上更纯粹，但在工程上超出 V0.1 目标太多。

V0.1 选择"语义化 messages + 最小公共字段集"的折中：
- 足够表达 Chat Completion 的语义
- 与所有主流 Provider 的语义可以一一映射
- 实现成本可控

## Consequences

### Positive

- **可扩展性**：新增 Provider 只需要写一个"IR → Provider"的适配器，不影响其他部分。
- **能力降级**：IR 可以携带能力声明，Router 据此做兼容性路由。
- **面试叙事**：IR 是项目的核心抽象，是面试时可以深入讲解的技术点。
- **未来基座**：IR 一旦稳定，可以支撑 SDK、嵌入式模式、多语言实现。

### Negative

- **初期成本高**：定义 IR 需要更多设计时间，比直接抄 OpenAI 格式慢。
- **过度设计风险**：IR 字段可能一时用不上，需要克制。
- **转换层多一跳**：多一层转换意味着多一次 JSON 映射，性能略差（但可忽略）。

### Neutral

- IR 的字段设计需要随 Provider 支持的增多而演进。这是长期维护成本。

## Alternatives Considered

### Alternative 1: 直接以 OpenAI 格式作为内部格式

- **优点**：实现最快，与现有生态兼容性最好。
- **拒绝理由**：无法精确表达非 OpenAI Provider 的语义，会在 V0.2 后遇到瓶颈。
- **参考**：One-API、New-API 采用了这个方案，在扩展到 100+ Provider 时遇到困难。

### Alternative 2: 使用已有的 IR 标准（如 OpenRouter、LiteLLM 的格式）

- **优点**：不用自己设计，社区可能有工具支持。
- **拒绝理由**：现有标准不够稳定，且 MixInfer 需要自己的 IR 作为差异化点。
- **参考**：LiteLLM 的内部格式接近 OpenAI 格式，没有做到语义级独立。

### Alternative 3: 完全自定义 IR（graph-based）

- **优点**：理论最优。
- **拒绝理由**：V0.1 范围过大，且没有实际需求支撑。

## References

- [LiteLLM Provider Abstraction](https://docs.litellm.ai/docs/providers)
- [OpenAI Chat Completions API](https://platform.openai.com/docs/api-reference/chat)
- [Anthropic Messages API](https://docs.anthropic.com/en/api/messages)
- [Conventional Commits](https://www.conventionalcommits.org/)