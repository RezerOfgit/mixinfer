# MixInfer 竞品调研

本文档记录 MixInfer 设计阶段对同类项目的调研，用于支撑架构决策与差异化判断。
目标是"记录我从竞品里学到了什么"，而不是"证明 MixInfer 更好"。

> **关于数据来源**：本文档中的定性判断基于对各项目公开仓库、文档与官方说明的
> 阅读。具体数字和漏洞编号如未给出来源，意味着**我无法核实**——因此以定性
> 描述呈现。一份标榜"诚实"的文档，不应该引用未经核实的精确数字。

## 1. 调研范围

| 项目 | 语言 | 定位 | 部署形态 | 核心特征 | 许可证 |
|---|---|---|---|---|---|
| LiteLLM | Python | SDK + AI Gateway | 进程内 SDK / Proxy | 广泛 Provider 覆盖、多级限流 | MIT（核心） / 商业（部分功能） |
| Bifrost | Go | 高性能 AI Gateway | 独立 HTTP / 嵌入式 Go SDK | 极致低延迟、集中式流处理 | 开源（具体许可需核实） |
| Portkey | TypeScript | 开源核心 + 托管控制平面 | Gateway + SaaS Control Plane | Guardrails、语义缓存 | MIT（Gateway） / 商业（治理） |
| APISIX AI Gateway | Lua | 通用网关 + AI 插件 | APISIX 数据平面 | 复用成熟网关、插件化 | Apache 2.0 |
| One-API / New-API | Go | 轻量 Token 中转 | 独立二进制 / Docker | 中文生态、轻量 | MIT |
| **MixInfer** | **Java** | **可嵌入 LLM 网关** | **独立 HTTP（V0.5+ 嵌入式）** | **语义 IR、Failover 边界、诚实治理** | **MIT** |

## 2. 行业收敛的四个架构模式

这四点是同类项目的共同选择，MixInfer 全部采纳：

| 模式 | 具体表现 |
|---|---|
| **Provider 抽象** | 每个项目都有明确的 Provider 接入机制，差异只在抽象方式 |
| **OpenAI 兼容入口** | 客户端统一用 `/v1/chat/completions`，差异在上游协议处理 |
| **虚拟密钥** | 治理实体（Key / Token / Consumer）与认证分离 |
| **Data / Control Plane 分离趋势** | Portkey 最彻底；LiteLLM / APISIX 部分；Bifrost / One-API 单进程 |

**这些不是创新，是这一代 AI 网关已经形成的共识。** 任何新的网关项目都必须覆盖，
否则用户不会迁移。

## 3. Provider 抽象方式的对比

这是各项目差异最大的维度。

| 项目 | 抽象方式 | 新 Provider 接入成本 | 内部表示的设计目标 |
|---|---|---|---|
| LiteLLM | Adapter + 模板方法 | 实现转换类 | **以 OpenAI 语义为锚点**（内部有自有结构，但字段与 OpenAI 高度对应） |
| Bifrost | Go 接口（大量方法） | 实现整个 Provider 接口 | **以 OpenAI 语义为锚点** |
| Portkey | 参数映射表 | 注册一个配置对象 | **以 OpenAI 语义为锚点** |
| APISIX | 协议检测 + 转换器 | 社区贡献转换器 | **以协议间互转为主**，无独立 IR |
| One-API | Adapter Pattern | 新增适配器 | **以 OpenAI 格式作为中间格式** |
| **MixInfer** | **语义 IR + Converter + Provider SPI** | **实现 1 个 Provider SPI 接口** | **独立于任何协议** |

**准确的观察**：同类项目的内部表示**都以 OpenAI 语义为锚点**——要么直接使用
OpenAI 格式，要么让内部结构紧贴 OpenAI 字段设计。**没有一个把"协议无关"作为
内部表示的第一设计目标**。

（需要说清：LiteLLM 并不是"裸 OpenAI JSON 直接透传"，它有自己的 `Message` /
`ModelResponse` 结构。但这些结构的字段选择明显以 OpenAI 为参照——这是务实的
选择，不是缺陷。）

**MixInfer 的取舍**：

- **收益**：新增入站协议（V0.5+ 的 Anthropic / Gemini）不需要动 IR 和上游适配器
- **代价**：Provider 覆盖目前只有 1 个通用实现；IR 的字段完整度不如成熟项目的内部结构；V0.4 的 `extensions` 字段是为"IR 不够用"预留的逃生舱

## 4. 可靠性能力的对比

| 项目 | Failover | 流式 Failover 边界 | 健康检查 | 同 Endpoint 重试 |
|---|---|---|---|---|
| LiteLLM | 有 | 有实现，未强调边界规则 | 有 | 有 |
| Bifrost | 有 | 有实现 | 有 | 有 |
| Portkey | 有 | 有实现 | 有 | 有 |
| APISIX | 有 | 有实现 | 主动 + 被动 | 有 |
| One-API | 简单实现 | 未见文档化 | 无 | 有限 |
| **MixInfer** | **有（跨 Endpoint）** | **明确："仅提交前"窗口** | **被动过滤（不主动探测）** | **明确不做（非幂等）** |

**关于 MixInfer 行的诚实补充**：

- **Provider 覆盖仅 1 个**——可靠性能力对"多 Provider 场景"的价值尚未验证
- **无 benchmark**——"流式 Failover 不引入显著开销"这一结论我未实测
- **同 Endpoint 重试"明确不做"是取舍，不是优势**——它放弃了"上游瞬时抖动"这类场景的恢复能力

**MixInfer 的三个明确判断**：

1. **流式 Failover 的边界**：白纸黑字写下"一旦 SSE 头发出，中途断流不再切换上游"
2. **不做主动健康探测**：LLM 场景的探测请求会产生真实费用
3. **不做同 Endpoint 重试**：LLM 请求非幂等，重试可能重复计费

这些判断竞品未必没有考虑过——但少有项目**把它写进设计文档并作为公开承诺**。

## 5. 治理能力的对比

| 项目 | 用量持久化 | 成本核算 | 限流 | 虚拟密钥 | 多租户 / RBAC | 规模画像 |
|---|---|---|---|---|---|---|
| LiteLLM | 外部数据库 + 缓存 | 有 | 多级（Global / Key / User / Team） | 有 | 有（部分付费） | 企业级 |
| Bifrost | 有 | 有 | 有 | 有 | 部分 | 高性能 |
| Portkey | 分析数据库 | 有 | 有 | 有 | 有（部分付费） | 平台级 |
| APISIX | 外部存储 | Token 计费 | Token 感知 | Consumer | 需自行组合 | 通用网关 |
| One-API | 关系数据库 | 额度 | 分组级 | Token | 分组 | 轻量 |
| **MixInfer** | **SQLite（V0.4）** | **配置定价（V0.4）** | **滑动窗口（V0.4）** | **有** | **V0.6+** | **单实例最小可用** |

**规模差距明显**：LiteLLM 的治理是"企业级"（多级限流 + 数据库 + 分布式缓存），
MixInfer 是**单实例最小可用**——这两个词是准确的，不是谦虚。

**但 MixInfer 有两个具体的语义判断，我认为值得单独提出**：

- **未知成本记 `null`，不记 0**：如果记 0，聚合报表会低估成本。竞品中我未见到文档化这条规则
- **被拒请求不进入限流窗口**：如果被拒请求也计数，持续重试的客户端会不断刷新窗口，永远被限流

这两条不是"功能多"，是"语义做对"。

## 6. 从竞品教训中"明确不做"的

**这是全文最有价值的部分** —— 不比较功能，比较风险意识。

| 项目 | 已知问题（定性） | MixInfer 的对应处理 |
|---|---|---|
| LiteLLM | 曾有版本被发现包含供应链投毒 | 单模块项目，依赖精简；CI 从源码构建，不使用第三方预构建镜像 |
| LiteLLM | 默认密钥问题：据安全扫描报告，相当比例的公开实例仍使用默认密钥 | 默认密钥仅用于开发；README 明确要求修改 |
| Bifrost | 曾出现管理 API 默认无认证的严重漏洞 | Admin API 使用独立 key；未配置时返回 404 而非放行 |
| Bifrost | 客户端断开时不关闭上游连接 | V0.2 已实现：检测写失败 → 关闭上游流 |
| Portkey | 曾出现 SSRF 漏洞（通过自定义 host 头） | 无自定义 host 能力；所有上游地址必须通过配置声明 |
| One-API | DockerHub 官方镜像曾被发现包含恶意代码 | 从源码多阶段构建；不使用第三方预构建镜像 |
| APISIX | 高频小 chunk 下存在 CPU 打满的已知问题 | **未做 benchmark，承认此处为未知项** |

**关于"未知项"**：本文档的目标不是声称"我解决了所有问题"——而是记录
"我看到了什么风险，我如何处理"。有些问题我确实不知道答案（比如性能
基准），写下来比装作没有更好。

## 7. 诚实的规模差距

**MixInfer 当前规模远小于上述项目**：

| 维度 | 最大竞品 | MixInfer |
|---|---|---|
| Provider 数量 | LiteLLM 100+ | 1 个通用 Provider（覆盖所有 OpenAI 兼容上游） |
| 入站协议 | Portkey / LiteLLM 多种 | 仅 OpenAI 兼容（V0.5+ 扩展） |
| 生产部署 | 全部可生产 | 单实例，无分布式 |
| 性能基准 | Bifrost 有官方基准 | 无 benchmark |
| 多租户 | LiteLLM / Portkey 有 | V0.6+ |
| 控制平面 / Web UI | Portkey 完整 | V0.5+ |
| 多语言 SDK | LiteLLM / Bifrost | 无计划 |
| 社区规模 | LiteLLM 万级 stars | 个人项目 |

**本文档的目标不是"证明 MixInfer 更好"**，而是记录设计决策的依据。

MixInfer 的定位是"可嵌入的轻量网关"——不追求覆盖所有场景，而是把
"协议无关"和"诚实治理"这两件事做对。

## 8. 参考资料

> 以下链接是各项目的公开仓库入口。本文档中引用的具体数字和漏洞编号
> 如未给出直接链接，意味着作者未能核实，故以定性描述呈现。

- LiteLLM: https://github.com/BerriAI/litellm
- Bifrost: https://github.com/maximhq/bifrost
- Portkey: https://github.com/Portkey-AI/gateway
- APISIX AI Gateway: https://apisix.apache.org/docs/apisix/plugins/ai-proxy/
- One-API: https://github.com/songquanpeng/one-api
- New-API: https://github.com/Calcium-Ion/new-api