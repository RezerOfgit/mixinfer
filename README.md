# MixInfer

> 可嵌入、可扩展的大模型统一接入与治理基础设施。

## 状态

✅ **V0.3 已发布** — Gateway Core & Reliability。

- V0.1：语义 IR 与最小网关闭环
- V0.2：流式数据平面与用量结算基础
- V0.3：多 Endpoint 路由、Failover、被动健康追踪

下一阶段：V0.4 用量与治理。

## 为什么做 MixInfer

大多数 LLM 网关以 OpenAI 请求格式作为内部格式。这在一开始工作得很好，
但当需要接入语义不相容的 Provider 时，格式会渗透到系统的每一层。

MixInfer 在协议之间保留**协议无关的内部表示（IR）**。入站协议、上游协议、
出站协议是三个独立的选项。新增协议不需要改 IR，新增上游不需要改路由层。

## 路线图

- [x] **V0.1** Technical MVP — 语义 IR 与最小网关闭环
- [x] **V0.2** Streaming Foundation — 流式数据平面与用量结算基础
- [x] **V0.3** Gateway Core & Reliability — 多 Endpoint 路由与故障转移
- [ ] **V0.4** Usage & Governance — 用量持久化、查询、成本、限流、额度
- [ ] **V0.5** Control Plane Foundation — 管理入口、配置管理、极简 Console
- [ ] **V0.6** Enterprise Foundation — Tenant、RBAC、租户隔离
- [ ] **V0.7** Extensibility — 自定义协议、Formatter、SDK
- [ ] **V1.0** Stable Release — 完整可部署、可复用

> V0.5 及之后的版本细节会在 V0.4 完成后重新评估，此处仅作方向标记。

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