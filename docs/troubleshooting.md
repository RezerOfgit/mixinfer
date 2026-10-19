# 常见问题排查

本文档记录 MixInfer 部署与使用过程中可能遇到的问题及排查步骤。
如果以下内容未能覆盖你的情况，请附上 `docker compose logs mixinfer-gateway`
的完整输出提 issue。

---

## 1. 启动类问题

### 容器启动后立即退出

**症状**：`docker compose ps` 显示容器状态为 `Exited`，日志末尾有异常堆栈。

**排查顺序**：

1. **看日志**：

   ```bash
   docker compose logs mixinfer-gateway
   ```

2. **环境变量缺失**：如果日志里有 `Unknown provider` 或 `No route configured`，检查 `docker-compose.yml` 里是否传了 `DEEPSEEK_API_KEY`（或对应上游密钥）。

3. **SQLite 权限**：如果日志里有 `SQLITE_CANTOPEN`，检查 `/app/data` 目录是否存在且可写。**不要**把宿主机目录挂载到 `/app/data`——容器内的非 root 用户可能与宿主机 UID 冲突。使用命名卷（默认配置）即可。

4. **端口占用**：如果日志里有 `Port 8080 was already in use`：

   ```powershell
   netstat -ano | findstr :8080
   tasklist | findstr <PID>
   ```

   确认占用进程后停止它，或改 `docker-compose.yml` 的端口映射。

### 启动后访问返回 404

**症状**：`curl http://localhost:8080/v1/chat/completions` 返回 404。

**原因**：URL 路径写错。MixInfer 只暴露 `/v1/chat/completions`，不是 `/v1/chat` 或 `/chat/completions`。

**验证**：

```bash
curl -X POST http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer sk-mixinfer-dev" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-flash","messages":[{"role":"user","content":"hi"}]}'
```

---

## 2. 认证类问题

### 返回 401 `invalid_api_key`

**原因**：`Authorization` 头缺失或密钥不匹配。

**排查**：

1. 检查 `application.yml` 里 `mixinfer.virtual-keys[].key`（或旧版 `mixinfer.api-keys[].key`）是否与请求里的一致。
2. 请求头必须是 `Authorization: Bearer sk-xxx`——必须带 `Bearer ` 前缀（有一个空格）。
3. 如果使用 `virtual-keys` 格式，检查每条是否显式声明了 `id`——**缺失 id 会导致启动失败**，不会走到认证这一步。

### 返回 401 `invalid_admin_key`

**原因**：访问 `/admin/**` 时用了客户端密钥，或 admin key 未配置。

**排查**：

1. `/admin/**` 需要 `MIXINFER_ADMIN_KEY` 对应的密钥，**不是** `sk-mixinfer-dev`。
2. 如果 `MIXINFER_ADMIN_KEY` 未设置，`/admin/**` 返回 **404**（而非 401）。这是刻意的设计——区分"功能未启用"与"凭据错误"。

---

## 3. 上游调用类问题

### 返回 502 `provider_error`

**原因**：上游调用失败，分类为可 failover 的错误但没有备用 Endpoint。

**排查**：

1. 检查 `DEEPSEEK_API_KEY` 是否有效：

   ```bash
   curl https://api.deepseek.com/v1/models \
     -H "Authorization: Bearer $DEEPSEEK_API_KEY"
   ```

2. 检查 `base-url` 是否正确——DeepSeek 是 `https://api.deepseek.com`（无 `/v1`），OpenAI 是 `https://api.openai.com/v1`（有 `/v1`）。
3. 看日志里是否有 `Provider ... failed (type=CONNECTION_FAILURE)`——说明网络不通，检查容器是否能访问外网。

### 返回 401 `authentication_error`（但客户端密钥是对的）

**原因**：**上游**返回了 401——`DEEPSEEK_API_KEY` 无效或过期。

**排查**：看日志里是否有 `Upstream returned HTTP 401`。这是上游的 401，不是 MixInfer 的 401，错误信息里会区分。

### 所有 Endpoint 失败，返回 502

**原因**：配置了多个 target，全部失败。

**排查**：看日志里每次尝试的 `type=xxx` 和 `attempt i/N`。常见原因：

- 全部 target 指向了同一个无效上游
- 全部 target 的 `base-url` 配置错误
- 全部 target 的 `api-key` 环境变量缺失

---

## 4. 流式类问题

### 流式请求收到数据但无法逐块显示

**症状**：`curl -N` 时数据一次性涌出，而不是逐块刷。

**原因**：客户端或中间层缓冲。**MixInfer 本身不缓冲**。

**排查**：

1. **确认用了 `-N`**：`curl -N` 禁用 curl 的输出缓冲。
2. **PowerShell 下的陷阱**：`curl` 是 `Invoke-WebRequest` 的别名，必须用 `curl.exe`。
3. **中间代理**：如果有 Nginx / Cloudflare 在前面，它们可能缓冲 SSE。MixInfer 已在响应头里设置 `X-Accel-Buffering: no`，但某些代理仍需手动配置。

### 流式请求中途断开，日志里有 `Client disconnected`

**这不是错误。** 客户端主动断开时，MixInfer 会立即关闭上游连接——这是设计行为，避免"客户端不要了但还在烧 token"。

如果希望流式响应完整返回，检查客户端的超时设置是否太短。

### 流式请求返回错误事件 `upstream_stream_error`

**原因**：上游在开始发送 chunk 之后断流。

**这是有意的设计**：一旦 SSE 头发出，MixInfer **不会切换到备用上游**——否则客户端会收到两段拼接的输出。

**排查**：看日志里的具体异常。常见原因：

- 上游生成超时
- 网络中途抖动
- 上游返回了非法 SSE 帧

**处理**：客户端应捕获 error 事件，自行决定是否重试。

---

## 5. 用量与治理类问题

### `/admin/usage/records` 返回空数组

**排查**：

1. 确认先发过请求。用量记录在**请求完成后**写入。
2. 检查 `MIXINFER_ADMIN_KEY` 是否正确：

   ```bash
   curl "http://localhost:8080/admin/usage/records?limit=10" \
     -H "Authorization: Bearer $MIXINFER_ADMIN_KEY"
   ```

3. 检查 SQLite 文件是否真的写入了数据：

   ```bash
   docker compose cp mixinfer-gateway:/app/data/mixinfer.db ./mixinfer-local.db
   ```

   然后用本地 SQLite 工具（如 DB Browser for SQLite）打开查看。

### 返回 429 `rate_limited`

**这不是错误**，是限流生效。

**响应头**：`Retry-After: <秒数>`，告诉客户端等多久后重试。

**排查**：

- 检查该 Virtual Key 的 `rate-limit.requests-per-minute` 配置
- 被拒请求**不进入**滑动窗口——所以等待 `Retry-After` 指定的时间后，请求会自然恢复

### 成本字段是 null

**原因**：以下情况之一：

1. 上游未返回 usage（某些上游在流式场景下不返回）
2. `(endpoint, upstream model)` 没有定价配置
3. 该次请求失败（如客户端断开、上游断流）

**这是有意的设计**——未知成本记 `null`，不记 0。聚合 API 返回的 `unpricedCount` 告诉你"有多少请求没有成本信息"。

---

## 6. 数据持久化类问题

### 重启容器后用量记录消失

**原因**：没有使用命名卷。

**排查**：

1. 确认 `docker-compose.yml` 里有：

   ```yaml
   volumes:
     - mixinfer-data:/app/data
   ```

2. **不要**删卷：`docker compose down -v` 会删除数据卷。只用 `docker compose down`（不带 `-v`）。

### 数据库锁（`SQLITE_BUSY`）

**症状**：日志里有 `SQLITE_BUSY` 或写入超时。

**原因**：并发写冲突。MixInfer 用单连接池（`maximumPoolSize = 1`）已尽可能避免，但多个 MixInfer 实例共享同一个 SQLite 文件仍会冲突。

**处理**：

- 单实例部署时不会出现
- 多实例部署时**不应共享**同一个 SQLite 文件——每个实例独立
- 如果需要共享状态，V0.4 尚未支持（未来版本会引入外部数据库）

### Migration 失败

**症状**：启动日志里有 `Applying migration VN failed`。

**排查**：

- 不要手动修改 `schema_version` 表
- 开发环境可以从零开始：

  ```bash
  docker compose down
  docker volume rm mixinfer_mixinfer-data
  docker compose up -d
  ```

- 生产环境**不要**删除数据库——`schema_version` 记录决定了哪些 migration 已应用，手动删除会导致数据丢失

---

## 7. 开发环境问题

### IDEA 跑测试时报告 `Sharing is only supported for boot loader classes`

**这不是错误**，是 JVM 的类数据共享（CDS）警告。

**处理**：已在 `pom.xml` 里用 `-Xshare:off` 关闭。如果仍有警告，在 IDEA 的 Run Configuration → VM options 里加上 `-Xshare:off`。

### 本地运行时 `/admin/**` 返回 404

**原因**：`application.yml` 里 `mixinfer.admin.api-key` 为空。

**处理**：设置环境变量 `MIXINFER_ADMIN_KEY`，或在 `application.yml` 里配置。

### 端口 8080 被占用（Windows）

**排查**：

```powershell
netstat -ano | findstr :8080
```

**常见占用者**：WSL2 / Docker Desktop / 之前未关闭的 Java 进程。

**处理**：

```powershell
net stop winnat
net start winnat
```

或改 `application.yml` 里的 `server.port`。

---

## 8. 获取帮助

如果以上内容未覆盖你的问题：

1. **看日志**：`docker compose logs mixinfer-gateway`
2. **提 issue**：附上日志、`application.yml`（**删除敏感信息**）、复现步骤
3. **确认版本**：`docker compose images`