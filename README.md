# MixInfer

> 可嵌入、可扩展的大模型统一接入与治理基础设施。

## 状态

✅ **V0.3 已发布** — 多 Endpoint 路由与故障转移。

- V0.1：语义 IR 与 OpenAI 兼容网关
- V0.2：SSE 流式、上游取消、用量结算、requestId 贯穿
- V0.3：多 Endpoint 路由、跨 Endpoint Failover、健康追踪、per-endpoint 超时

下一阶段：V0.4 用量与治理。

## 为什么做 MixInfer

大多数 LLM 网关以 OpenAI 请求格式作为内部格式。这在一开始工作得很好，
但当需要接入语义不相容的 Provider 时，格式会渗透到系统的每一层。

MixInfer 在协议之间保留**协议无关的内部表示（IR）**。入站协议、上游协议、
出站协议是三个独立的选项。新增协议不需要改 IR，新增上游不需要改路由层。

## 路线图

- [x] **V0.1** 语义 IR 与 OpenAI 兼容网关
- [x] **V0.2** 流式数据平面
  - [x] 端到端 SSE 流式
  - [x] 客户端断开时取消上游
  - [x] 用量结算状态机
  - [x] requestId 贯穿
- [x] **V0.3** 多 Endpoint 路由与可靠性
  - [x] 一个逻辑模型对应多个上游
  - [x] 声明顺序 Failover
  - [x] 失败类型分类（可 failover / 不可 failover）
  - [x] 流式 open 阶段 Failover
  - [x] 内存级 Endpoint 健康追踪
  - [x] per-endpoint 超时（connect / request）
- [ ] **V0.4** 用量与治理 — 成本、配额、限流
- [ ] **V0.5** 控制平面 — Web 控制台，Provider / Endpoint / Key 管理
- [ ] **长期** SDK、嵌入模式、多协议入站

## 架构

```text
客户端
  │
  │ OpenAI 兼容 HTTP（流式或非流式）
  ▼
MixInfer Gateway
  │
  │  认证 → 转换为 IR → 路由 → 选择 Endpoint → 调用上游
  │                                     ↓
  │                                失败 → 切换下一个 Endpoint
  ▼
上游 LLM（任意 OpenAI 兼容端点）
```

完整设计见 [docs/architecture.md](docs/architecture.md)。

## 快速开始

### 方式一：Docker（推荐）

```bash
# routing.md. 设置上游 API Key
export DEEPSEEK_API_KEY=sk-your-key-here

# 2. 启动网关
docker compose up --build

# 3. 发送非流式请求
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-mixinfer-dev" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-flash",
    "messages": [{"role": "user", "content": "Say hi"}]
  }'

# 4. 发送流式请求（-N 禁用缓冲，逐块输出）
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-mixinfer-dev" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-flash",
    "stream": true,
    "messages": [{"role": "user", "content": "Count from routing.md to 10"}]
  }'
```

> **Windows PowerShell 用户**：用 `$env:DEEPSEEK_API_KEY="sk-your-key"` 代替 `export`，
> 请求体通过文件传入（`-d "@body.json"`）——PowerShell 对单引号的处理和 bash 不同。

### 方式二：从源码启动

需要 JDK 17+ 与 Maven 3.9+。

```bash
cd mixinfer-gateway
./mvnw spring-boot:run
```

### 配置

编辑 `mixinfer-gateway/src/main/resources/application.yml`：

```yaml
mixinfer:
  api-keys:
    - key: sk-mixinfer-dev    # 面向客户端的 Key
      name: default
  providers:
    - name: openai-compatible    # 实例名（route 中引用）
      type: openai-compatible    # 实现类型（默认值，可省略）
      base-url: https://api.deepseek.com
      api-key: ${DEEPSEEK_API_KEY}
  routes:
    - model: deepseek-flash
      targets:
        - provider: openai-compatible
```

**同一份客户端代码可以对接任意 OpenAI 兼容上游**——只改 `base-url` 即可。
在 OpenAI、DeepSeek、OpenRouter、自建 vLLM 或公司内部网关之间切换，不需要改代码。

### 多 Endpoint Failover

为一个逻辑模型配置多个上游，按声明顺序尝试：

```yaml
providers:
  - name: primary
    base-url: https://api.deepseek.com
    api-key: ${PRIMARY_KEY}
  - name: backup
    base-url: https://api.openai.com/v1
    api-key: ${BACKUP_KEY}

routes:
  - model: deepseek-flash
    targets:
      - provider: primary
      - provider: backup
```

主路失败时，MixInfer 会按失败类型判断是否切换到备路。
连接失败、超时、HTTP 5xx、HTTP 429 会触发 failover；HTTP 4xx（除 408/429）直接返回错误。

## 设计决策

- [ADR-001](docs/adr/001-use-semantic-ir.md)：为什么用语义 IR，而不是协议级转换
- [ADR-002](docs/adr/002-streaming-data-plane.md)：流式数据平面与用量结算
- [ADR-003](docs/adr/003-multi-endpoint-routing.md)：多 Endpoint 路由与可靠性

## 许可证

MIT