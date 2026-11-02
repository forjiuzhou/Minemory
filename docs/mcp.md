# 自记 MCP

函数名是 `ownlog-mcp`。任意支持 Streamable HTTP 的代理（Cursor、Claude、ChatGPT、Grok Bot 等）只要有地址和一只 `olk_` 令牌，就能读你的自记。手机应用本身不调用这个接口。这个接口不能操作手机。本机控机是另一条路，见 [control.md](control.md)。

边缘函数入口是 `supabase/functions/ownlog-mcp/index.ts`，只负责读 `SUPABASE_URL` 和 `SUPABASE_SERVICE_ROLE_KEY`，然后把请求交给同一套工具代码。在自己的机器上跑见 [self-host.md](self-host.md)。

默认时区是 **Asia/Singapore（UTC+8）**。工具返回的时间一律是 ISO 8601。只传 `YYYY-MM-DD` 时，按这个时区切当天；可以用参数 `tz` 换成别的 IANA 时区或 `UTC+8` 这种固定偏移。

## 部署到 Supabase

先在 SQL Editor 里跑完迁移，至少包括 `supabase/migrations/20261022200000_agent_mcp.sql`。更早的迁移要已经在这个项目上。

然后部署边缘函数，**名字必须是 `ownlog-mcp`**，**`verify_jwt` 必须是 false**。网关如果还验用户 JWT，`olk_` 令牌会被拒掉。`supabase/config.toml` 里已经写了 `verify_jwt = false`。用 Supabase 的 `deploy_edge_function` 时也要显式传 `verify_jwt: false`，入口文件 `index.ts`，把 `supabase/functions/ownlog-mcp/` 下面的源文件一并上传。这个函数不需要额外密钥：`SUPABASE_URL` 和 `SUPABASE_SERVICE_ROLE_KEY` 由平台注入。

```bash
supabase functions deploy ownlog-mcp --no-verify-jwt
```

地址：

```text
https://<project-ref>.supabase.co/functions/v1/ownlog-mcp
```

## 签发令牌

在 SQL Editor（postgres）或用 service role 执行。明文只返回这一次，库里只存 sha-256。

```sql
select public.create_agent_token('grok-bot', '<user uuid>');
```

用户 uuid 可以在 Authentication → Users 里看到，或：

```sql
select id, email from auth.users;
```

只读、并且只许看收件类型：

```sql
select public.create_agent_token(
    'inbox-reader',
    '<user uuid>',
    array['share', 'note', 'photo', 'voice', 'screen_ocr'],
    false
);
```

允许 `annotate` 改标签、摘要和整理时间：

```sql
select public.create_agent_token('organizer', '<user uuid>', '{*}', true);
```

`scopes` 里的 `*` 表示全部事件类型。否则每次查询都会收窄到令牌列出的类型。

撤销（按名字会撤销所有同名且尚未撤销的令牌；要精确就用 id）：

```sql
update public.agent_tokens
set revoked_at = now()
where name = 'grok-bot'
  and revoked_at is null;
```

缺令牌、令牌不对、或已撤销，接口返回 HTTP 401。每次 `tools/call` 会写入 `agent_access_log`（参数里没有令牌本身）。

`agent_tokens` 和 `agent_access_log` 开了 RLS，且没有给 `anon` / `authenticated` 授权，手机和浏览器用户碰不到这两张表。

## 客户端

无状态 Streamable HTTP。响应是 JSON，不需要单独的 SSE 长连接。请求请带上：

```http
Authorization: Bearer olk_...
Accept: application/json, text/event-stream
Content-Type: application/json
```

通用配置：

```json
{
  "mcpServers": {
    "ownlog": {
      "type": "http",
      "url": "https://<project-ref>.supabase.co/functions/v1/ownlog-mcp",
      "headers": {
        "Authorization": "Bearer olk_把签发时看到的明文贴在这里",
        "Accept": "application/json, text/event-stream"
      }
    }
  }
}
```

`GET` 和 `DELETE` 返回 405。这个服务不维持会话，忽略 `Mcp-Session-Id`。协议版本由服务端按客户端的 `initialize` 协商（含 2024-11-05、2025-03-26、2025-06-18 以及 SDK 支持的更新版本）。

## 工具

| 工具 | 作用 |
| --- | --- |
| `search` | 按标题和正文搜索。`query`，可选 `types`、`from`、`to`、`app`（包名或应用名）、`limit`（默认 20，最多 50）、`tz` |
| `timeline` | 一段时间的正序事件。`date` 或 `from`/`to`，可选 `types`、`limit`（默认 80，最多 200）、`tz`。连续且正文相同的 `ui_text` 收成一条，`collapsed_count` 是条数 |
| `get_event` | 按 uuid 读完整一行。`extras.file_path` 若指向 inbox，附带 1 小时的 `file_url` |
| `app_usage` | 按应用汇总时长和会话数。用 `app_bg.extras.duration_ms`，没有时长时按 `session_id` 把 `app_fg` / `app_bg` 配成一对 |
| `places` | 位置点。离上一个保留点不到 50 米的并进同一簇，带 `first_seen` / `last_seen` |
| `inbox` | 默认只看还没整理的 `share` / `note` / `photo` / `voice` / `screen_ocr`。`unorganized: false` 连已整理的一起看 |
| `annotate` | 写 `tags`、`summary`，或 `mark_organized: true`。只有 `can_write` 的令牌可以调用。`mark_organized` 为 false 不会清空 `organized_at` |
| `stats` | 最近 N 天（默认 7，最多 90）按类型和当地日期计数 |

列表里的长正文截到 800 字，并带 `content_truncated: true`。`get_event` 放到 20000 字，仍可能截断。原文件只在 `get_event` 里给签名地址。

令牌范围之外的类型不会返回。问了一条范围外的记录时，和「没有这条」是同一句话。

## 搜索怎么打分

迁移打开 `pg_trgm`，并在 `lower(title || content)` 上建了 GIN 索引。`public.search_events` 把三样混在一起排序：pg_trgm 的 `similarity`、原文 `ILIKE`、以及 `ownlog_char_score`（按字符二元组计分）。最后这项是为了中文：pg_trgm 的「词字符」常常不含汉字，单靠 trigram 会把中文当成没有重叠。

## 以后的语义搜索

现在没有向量列，也没有 pgvector。以后如果要做语义搜索，可以加一列可空的 `events.embedding`（例如 `extensions.vector(1536)`），不要给默认值，旧行继续只用上面的字面匹配。索引用 HNSW、`vector_cosine_ops`，并且 `where embedding is not null`。查询时把 `1 - (embedding <=> 查询向量)` 和现有的字面分混在一起。向量由外部代理写入，手机不写。这一步先不做。

## 代码放在哪

边缘函数目录（部署时整份上传，入口 `index.ts`）：

- `supabase/functions/ownlog-mcp/index.ts`
- `supabase/functions/ownlog-mcp/http.ts`
- `supabase/functions/ownlog-mcp/tools.ts`
- `supabase/functions/ownlog-mcp/auth.ts`
- `supabase/functions/ownlog-mcp/pure.ts`
- `supabase/functions/ownlog-mcp/store.ts`
- `supabase/functions/ownlog-mcp/supabase_store.ts`
- `supabase/functions/ownlog-mcp/pure_test.ts`
- `supabase/functions/ownlog-mcp/http_test.ts`

VPS 适配器不打进边缘函数的入口依赖里：

- `server/standalone.ts`
- `server/postgres_store.ts`
- `server/files.ts`
- `server/files_test.ts`

工具、鉴权和查询在 `tools.ts` / `http.ts` / `auth.ts` / `pure.ts`，只依赖 `Store`。`index.ts` 是 Supabase 适配器，`server/standalone.ts` 是 Postgres 适配器。

## 测试

本机有 Deno 2。纯函数和 HTTP 用 Deno 的测试器，不依赖 vitest：

```bash
deno test -A supabase/functions/ownlog-mcp/pure_test.ts supabase/functions/ownlog-mcp/http_test.ts server/files_test.ts
```
