# MixInfer

> Modular, Embeddable LLM Gateway & Inference Infrastructure

可嵌入、可扩展的大模型统一接入与治理基础设施。

## Status

✅ **V0.2 released** — streaming data plane with usage settlement.

- V0.1: OpenAI-compatible gateway with semantic IR
- V0.2: SSE streaming, upstream cancellation, usage settlement, request correlation

Working on: V0.3 Routing & Reliability.

## Why MixInfer

Most LLM gateways use OpenAI's request format as their internal format.
This works until you need to support a provider whose semantics do not fit —
then the format leaks into every layer.

MixInfer keeps a **provider-neutral internal representation (IR)** between
protocols. Inbound format, upstream format, and outbound format are
independent choices. Adding a new protocol does not require touching the IR;
adding a new provider does not require touching the routing layer.

## Roadmap

- [x] **V0.1** OpenAI-Compatible Gateway with Semantic IR
- [x] **V0.2** Streaming Foundation
    - [x] SSE streaming end to end
    - [x] Upstream cancellation on client disconnect
    - [x] Usage settlement state machine
    - [x] requestId propagation
- [ ] **V0.3** Routing & Reliability — multi-endpoint, weighted routing, fallback, retry
- [ ] **V0.4** Usage & Governance — cost, quota, rate limit
- [ ] **V0.5** Control Plane — Web console, provider / endpoint / key management
- [ ] **Long-term** SDKs, embedded mode, multi-protocol inbound

## Architecture

```
Client
  │
  │ OpenAI-compatible HTTP (streaming or not)
  ▼
MixInfer Gateway
  │
  │  authenticate → convert to IR → route → invoke upstream
  │
  ▼
Upstream LLM (any OpenAI-compatible endpoint)
```

See [docs/architecture.md](docs/architecture.md) for the full design.

## Quick Start

### Option 1: Docker (recommended)

```bash
# 1. Set your upstream API key
export DEEPSEEK_API_KEY=sk-your-key-here

# 2. Start the gateway
docker compose up --build

# 3. Send a non-streaming request
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-mixinfer-dev" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-flash",
    "messages": [{"role": "user", "content": "Say hi"}]
  }'

# 4. Send a streaming request
curl -N -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-mixinfer-dev" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-flash",
    "stream": true,
    "messages": [{"role": "user", "content": "Count from 1 to 10"}]
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
  usage:
    file: ${MIXINFER_USAGE_FILE:logs/usage.log}
```

**The same client code works with any OpenAI-compatible upstream** — just change `base-url`.
No code change is required to switch between OpenAI, DeepSeek, OpenRouter, a self-hosted vLLM, or a company-internal gateway.

## Design Decisions

- [ADR-001](docs/adr/001-use-semantic-ir.md): Semantic IR instead of protocol-level conversion
- [ADR-002](docs/adr/002-streaming-data-plane.md): Streaming data plane and usage settlement

## License

MIT