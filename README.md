# MixInfer

> Modular, Embeddable LLM Gateway & Inference Infrastructure

可嵌入、可扩展的大模型统一接入与治理基础设施。

## Status
⚠️ Early Development — V0.1 in progress
目标：能真正转发一次 LLM 请求

## Why MixInfer
（3–5 行差异化叙事，指向语义级 IR）

## Roadmap
- [ ] V0.1 单 Provider 转发
- [ ] V0.2 语义 IR + 多 Provider
- [ ] V0.3 路由 / Fallback / 管理界面
- [ ] V0.4 计量 / 预算
- [ ] Long-term 多语言 SDK

## Architecture
（链路图，先放你那张 Client → MixInfer → Provider 的图）

## Quick Start

### Option 1: Docker (recommended)

```bash
# 1. Set your upstream API key
export DEEPSEEK_API_KEY=sk-your-key-here

# 2. Start the gateway
docker compose up --build

# 3. In another terminal, send a request
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-mixinfer-dev" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-flash",
    "messages": [{"role": "user", "content": "Say hi"}]
  }'
```

> **Windows PowerShell users:** use `$env:DEEPSEEK_API_KEY="sk-your-key"` instead of `export`,
> and pass the request body via a file (`-d "@body.json"`) — PowerShell does not treat
> single quotes the way bash does.

### Option 2: From source

Requires JDK 17+ and Maven 3.9+.

```bash
cd mixinfer-gateway
./mvnw spring-boot:run
```

### Configuration

Edit `mixinfer-gateway/src/main/resources/application.yml`:

```yaml
mixinfer:
  api-keys:
    - key: sk-mixinfer-dev    # Client-facing key
      name: default
  providers:
    - name: openai-compatible
      base-url: https://api.deepseek.com   # Any OpenAI-compatible endpoint
      api-key: ${DEEPSEEK_API_KEY}
      models:
        - deepseek-flash
  routes:
    - model: deepseek-flash
      provider: openai-compatible
```

**The same client code works with any OpenAI-compatible upstream** — just change `base-url`.
No code change is required to switch between OpenAI, DeepSeek, OpenRouter, a self-hosted vLLM, or a company-internal gateway.

## License
MIT