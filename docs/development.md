# 开发指南

面向想修改 MixInfer 代码或本地调试的开发者。
部署与使用说明见 [README](../README.md)；设计与决策见 [docs/adr/](./adr/)。

## 1. 环境要求

| 工具 | 版本 | 用途 |
|---|---|---|
| JDK | 17+ | 编译与运行 |
| Maven | 3.9+（或使用 `mvnw`） | 构建 |
| Docker | 24+ | 本地验证与部署 |
| Git | 任意近期版本 | 版本控制 |

Windows 下建议使用 PowerShell 作为终端。
**不要**在 PowerShell 里用 `curl`（它是 `Invoke-WebRequest` 的别名，参数不兼容）——用 `curl.exe`。

## 2. 项目结构

```
mixinfer/
├── mixinfer-gateway/           ← Spring Boot 应用（唯一的 Java 模块）
│   ├── pom.xml
│   ├── mvnw / mvnw.cmd
│   ├── Dockerfile
│   └── src/
│       ├── main/java/com/mixinfer/
│       │   ├── api/            控制器（Chat / Admin）
│       │   ├── auth/           VirtualKey / Filter
│       │   ├── config/         MixInferProperties
│       │   ├── converter/      DTO ↔ IR
│       │   ├── domain/         IR / UsageRecord
│       │   ├── exception/      统一异常
│       │   ├── metering/       用量、成本、Repository
│       │   ├── openai/         OpenAI 兼容 DTO
│       │   ├── provider/       LlmProvider / StreamingLlmProvider SPI
│       │   ├── ratelimit/      限流
│       │   ├── router/         路由 / Failover / 健康
│       │   ├── service/        ChatCompletionService
│       │   ├── storage/        SQLite 配置与实现
│       │   ├── streaming/      SSE 解析与响应
│       │   └── web/            RequestIdFilter
│       └── test/java/com/mixinfer/    与主代码镜像的测试结构
├── docs/
│   ├── architecture.md
│   ├── governance.md
│   ├── routing.md
│   ├── streaming.md
│   ├── troubleshooting.md
│   ├── competitive-analysis.md
│   └── adr/                    ADR-001 ~ ADR-004
├── docker-compose.yml
├── README.md
└── .github/workflows/          CI / Release
```

**分层原则**：`api` 只调 `service`；`service` 组合 `converter` / `router` / `provider` / `metering`；`domain` 被所有层引用但不依赖任何层。

## 3. 本地开发流程

### 3.1 首次构建

```powershell
git clone https://github.com/RezerOfgit/mixinfer.git
cd mixinfer/mixinfer-gateway
./mvnw clean package
```

**如果报错 `Permission denied`**（Windows）：

```powershell
.\mvnw.cmd clean package
```

### 3.2 运行测试

**全量**：

```powershell
./mvnw clean test
```

**单个测试类**：

```powershell
./mvnw test -Dtest=SseEventParserTest
```

**单个测试方法**：

```powershell
./mvnw test -Dtest=SseEventParserTest#should_parse_single_data_event
```

### 3.3 本地运行

**方式一：Spring Boot Maven 插件**

```powershell
$env:DEEPSEEK_API_KEY="sk-你的key"
./mvnw spring-boot:run
```

**方式二：Docker Compose**（推荐，与生产一致）

```powershell
cd ..
$env:DEEPSEEK_API_KEY="sk-你的key"
$env:MIXINFER_ADMIN_KEY="sk-admin-dev"
docker compose up --build
```

**验证**：

```powershell
curl.exe -X POST http://localhost:8080/v1/chat/completions `
  -H "Authorization: Bearer sk-mixinfer-dev" `
  -H "Content-Type: application/json" `
  -d "@body.json"
```

## 4. 常见开发任务

### 4.1 添加一个新的上游 Provider

**当前只有 `OpenAICompatibleProvider`**——覆盖所有 OpenAI 兼容上游（DeepSeek、OpenRouter、vLLM 等）。**接入新上游只需改 `application.yml`**，不需要写代码。

**如果要接入非 OpenAI 兼容协议**（比如原生 Anthropic）：

1. 在 `provider/` 下新建 `AnthropicProvider`（实现 `LlmProvider` 和 `StreamingLlmProvider`）
2. 在 `openai/` 同级新增 `anthropic/` 包，放 Anthropic 协议的 DTO
3. 在 `converter/` 下新增 `AnthropicTollmConverter` 和 `LlmToAnthropicConverter`
4. 配置 `mixinfer.providers[].type: anthropic`
5. 在 `ProviderRegistry` 里，`type` 已经通过配置解析——不需要改 Registry 本身

**ADR-001 说明为什么不复用 OpenAI 格式作为内部格式**——读一遍再动手。

### 4.2 添加一个新的 Failover 策略

**当前只有 `SequentialSelector`**（按声明顺序）。

1. 实现 `EndpointSelector` 接口
2. 在 `RoutePlanner` 的构造器里选择注入哪个实现——或者用 `@Primary` 标记默认
3. ADR-003 里有"加权路由推到 V0.3.1"的记录——动手前先读

### 4.3 添加一个新的 Admin 查询端点

1. 在 `UsageRepository` 接口加方法
2. 在 `SqliteUsageRepository` 实现（注意 SQL 注入防护——用 `?` 占位符）
3. 在 `AdminUsageController` 加 `@GetMapping`
4. **不要忘记**：`AdminKeyFilter` 会自动保护 `/admin/**`，不需要额外加鉴权

### 4.4 修改配置字段

1. 在 `MixInferProperties` 加字段（给合理默认值）
2. 在 `application.yml` 加示例
3. 如果影响行为，**先写 ADR**
4. 更新 README 的"配置"段

## 5. 调试

### 5.1 日志

**日志格式**：

```
HH:mm:ss.SSS LEVEL [thread] [requestId] logger - message
```

- `requestId` 来自 MDC，贯穿一次请求的所有日志
- 用 `grep <requestId>` 捞出全链路

**日志级别**：

```yaml
logging:
  level:
    com.mixinfer: DEBUG
```

**生产环境不要开 DEBUG**——SQL 参数、SSE chunk 都会打印。

### 5.2 追踪一次请求

**Step 1：发请求并拿到 requestId**

```powershell
curl.exe -i -X POST http://localhost:8080/v1/chat/completions `
  -H "Authorization: Bearer sk-mixinfer-dev" `
  -H "Content-Type: application/json" `
  -d "@body.json"
```

响应头里有 `X-MixInfer-Request-Id: <uuid>`。

**Step 2：在日志里搜这个 id**

```powershell
docker compose logs mixinfer-gateway | Select-String "<uuid>"
```

**Step 3：查用量记录**

```powershell
curl.exe "http://localhost:8080/admin/usage/records?requestId=<uuid>" `
  -H "Authorization: Bearer sk-admin-dev"
```

**Step 4：如果涉及成本，查定价**

日志里搜 `Pricing loaded`——能看到本次使用的定价与版本。

### 5.3 查看 SQLite 数据库

**容器里没有 `sqlite3` 命令行**。三种方式：

**方式一：Admin API**（推荐）

```powershell
curl.exe "http://localhost:8080/admin/usage/records?limit=20" `
  -H "Authorization: Bearer sk-admin-dev"
```

**方式二：复制数据库到宿主机**

```powershell
docker compose cp mixinfer-gateway:/app/data/mixinfer.db ./mixinfer-local.db
```

用 [DB Browser for SQLite](https://sqlitebrowser.org/) 打开。

**方式三：宿主机临时起一个 sqlite 容器**

```powershell
docker run --rm -it -v "${PWD}:/data" keinos/sqlite3 sqlite3 /data/mixinfer-local.db
```

### 5.4 流式调试

```powershell
curl.exe -N -X POST http://localhost:8080/v1/chat/completions `
  -H "Authorization: Bearer sk-mixinfer-dev" `
  -H "Content-Type: application/json" `
  -d "@stream-body.json"
```

`-N` 禁用 curl 的输出缓冲——不加这个，你会觉得"流式没生效"。

**PowerShell 下的额外坑**：`curl` 是别名，**必须 `curl.exe`**。

## 6. 提交规范

**Conventional Commits**：

```
<type>: <短描述>

<正文：为什么改，改了哪些>
```

**type 取值**：`feat` / `fix` / `refactor` / `test` / `docs` / `chore` / `build` / `ci` / `perf`

**commit message 用英文**——GitHub 的国际惯例，也是 grep 的基础。
正文用英文；如果团队内沟通需要，可以在 commit 描述之外用中文，**但 commit 本身用英文**。

**单个 commit 应是一个逻辑单元**——能独立 revert，不破坏系统。

## 7. CI 与发布

### 7.1 CI

**`.github/workflows/ci.yml`**：每次 push / PR 到 main，跑 `./mvnw clean test`。

**本地复现 CI**（在干净环境下跑一次）：

```powershell
cd mixinfer-gateway
./mvnw -B clean test
```

`-B` 是 batch mode——CI 里使用的模式。

### 7.2 发布流程

**打 tag 前**：

1. 确认 `main` 分支上所有测试通过（CI 绿色）
2. 更新 `README` 的状态与路线图
3. 如果涉及定价 / Docker / 配置，检查 `docs/` 是否同步

**打 tag**：

```powershell
git tag -a v0.5.0 -m "..."
git push origin main
git push origin v0.5.0
```

**GitHub Release**：

- 去 GitHub → Releases → Draft a new release
- 选 `v0.5.0` tag
- 粘贴 Release notes（**中文**，与历史 Release 一致）
- 发布

**注意**：`release.yml` 工作流在 tag push 时触发 Docker 构建与推送。
**如果还没配置 Docker Hub secrets**，这个 job 会失败——不影响测试通过，
但需要在 GitHub → Settings → Secrets 里配 `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN`。

## 8. 关于"为什么"的文档

**每个重要决策都有一份 ADR**：

- [ADR-001](./adr/001-use-semantic-ir.md) — 为什么用语义 IR
- [ADR-002](./adr/002-streaming-data-plane.md) — 流式数据平面与结算
- [ADR-003](./adr/003-multi-endpoint-routing.md) — 多 Endpoint 路由与可靠性
- [ADR-004](./adr/004-usage-cost-rate-limit-quota.md) — 用量、成本、限流、配额

**改代码之前，先读对应的 ADR**——被拒绝的方案、取舍的边界，都在里面。

如果发现了没写进 ADR 但应该记录的决策——**先写 ADR，再改代码**。