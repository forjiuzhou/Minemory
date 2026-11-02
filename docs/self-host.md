# 在 VPS 上跑自记 MCP

同一套工具可以不经过 Supabase Edge，直接在一台机器上听端口。数据从 `DATABASE_URL` 读 Postgres，文件从本机目录或 S3 兼容存储读。边缘函数那条路见 [mcp.md](mcp.md)。

进程入口是 `server/standalone.ts`。它不读 `SUPABASE_SERVICE_ROLE_KEY`。

## 机器上要有什么

- Deno 2
- 一台 Postgres。可以是 Supabase 的数据库（推荐，迁移已经在那边），也可以是你自己的实例
- Caddy，或其他能做 HTTPS 的反向代理
- 若要给 `get_event` 签文件地址：本机目录，或一套 S3 兼容的钥匙

手机应用仍然把事件写进 Supabase，并用同一个登录会话领取 `phone_commands`。这份服务在令牌允许时可以改标签，也可以把控机命令插进同一张表。它不会替代手机的上传，也不持有手机。手机没开远程控制时，`phone_*` 只会告诉你不在线。

## 数据库

迁移文件包括 `supabase/migrations/20261022200000_agent_mcp.sql`、`supabase/migrations/20261102180000_phone_control.sql`，以及它们之前的自记迁移。在 Supabase 上用 SQL Editor 或 `supabase db push` 执行即可。控机表必须和手机登录的是同一个库。

VPS 若直接连 Supabase 的 Postgres，用 Dashboard → Database 里的连接串，端口 5432 的直连即可。事务池（6543）也能用，进程看到池化地址时会关掉 prepared statements。连接串放进环境变量 `DATABASE_URL`，不要写进仓库。

```text
DATABASE_URL=postgresql://postgres.<ref>:<password>@db.<ref>.supabase.co:5432/postgres?sslmode=require
```

签发令牌仍在数据库里做，明文只出现一次：

```sql
select public.create_agent_token('vps', '<user uuid>');
-- 允许这只令牌操作手机：
select public.create_agent_token('vps-phone', '<user uuid>', '{*}', false, true);
```

撤销：

```sql
update public.agent_tokens
set revoked_at = now()
where name = 'vps' and revoked_at is null;
```

整库搬到自己的 Postgres 时，用 `pg_dump` 带走 `public`，并带上 `auth.users` 里对应的用户行，因为 `events.user_id` 和 `agent_tokens.user_id` 引用它。更早的迁移还会碰到 Supabase 的 `storage` 和 `auth.uid()`。空的 Postgres 上不要指望那些迁移原样跑通。实用的做法是：迁移留在 Supabase，VPS 只拿连接串。

若一定要从空库开始，先有一个最小的 `auth.users`，再导入事件，最后跑 `20261022200000_agent_mcp.sql`：

```sql
create schema if not exists auth;
create table if not exists auth.users (
    id uuid primary key
);
insert into auth.users (id) values ('<user uuid>') on conflict do nothing;
```

`events` 表需要和手机写进去的列一致（含 `user_id`、`tags`、`summary`、`organized_at`）。从 Supabase 做一次 `pg_dump --schema=public` 再恢复，比手写建表省事。

连接用的角色要能读 `events`、读写 `agent_tokens` / `agent_access_log`，并能执行 `search_events`。表的属主（通常是 `postgres`）默认就行，而且属主不受这些表上「没有 policy」的 RLS 限制。

## 文件放哪

`get_event` 看到 `extras.file_path` 时去签名。路径形如：

```text
<user-uuid>/<yyyy>/<mm>/<uuid>.jpg
```

两条存储，配了一套就不用再配另一套。

本机目录：

```text
FILES_DIR=/var/lib/ownlog/inbox
FILE_URL_SECRET=<一段长随机字符串>
PUBLIC_BASE_URL=https://ownlog.example.com
```

磁盘上的文件是 `$FILES_DIR` 加上面的相对路径，例如 `/var/lib/ownlog/inbox/<user-uuid>/2026/10/<uuid>.jpg`。签名地址挂在 `PUBLIC_BASE_URL/files/...`，1 小时过期，由这个进程自己送出文件。密钥一变，旧链接立刻失效。

S3 兼容（MinIO、Cloudflare R2，或你自己的存储）：

```text
S3_ENDPOINT=https://s3.example.com
S3_BUCKET=inbox
S3_ACCESS_KEY_ID=...
S3_SECRET_ACCESS_KEY=...
S3_REGION=us-east-1
```

对象键就是 `file_path`，用路径风格的预签名 GET，同样 1 小时。设了 `S3_BUCKET` 就走 S3，不再读 `FILES_DIR`。

手机今天把原件放在 Supabase Storage 的 `inbox` 桶。要在 VPS 上打开这些文件，把对象同步到 `FILES_DIR`（保持相对路径），或让 `S3_*` 指向同一批对象。不同步的话，事件正文仍能查，只是没有 `file_url`。

## 启动

在仓库根目录：

```bash
deno run -A server/standalone.ts
```

或：

```bash
deno serve -A --port=8788 --host=127.0.0.1 server/standalone.ts
```

`deno run` 默认只听 `127.0.0.1:8788`（`BIND`、`PORT` 可改）。`deno serve` 用它自己的 `--host` 和 `--port`，请写成 `127.0.0.1`，不要听在所有网卡上。MCP 的路径是 `/` 或 `/mcp`。

`/etc/ownlog.env` 权限设成 `600`。systemd 示例：

```ini
[Service]
WorkingDirectory=/opt/ownlog
EnvironmentFile=/etc/ownlog.env
ExecStart=/usr/local/bin/deno run -A /opt/ownlog/server/standalone.ts
User=ownlog
Restart=on-failure
```

## Caddy

只把 443 暴露出去，8788 留在本机。

```caddy
ownlog.example.com {
    reverse_proxy 127.0.0.1:8788
}
```

代理配置里的地址：

```text
https://ownlog.example.com/mcp
```

请求头和边缘函数一样：`Authorization: Bearer olk_...`，并带上 `Accept: application/json, text/event-stream`。

## 备份

数据库和文件分开备。下面假设备份目录 `/var/backups/ownlog` 已经存在，并且只有这个服务的用户能读。

```bash
pg_dump --format=custom --file "/var/backups/ownlog/ownlog-$(date +%F).dump" "$DATABASE_URL"
tar -C /var/lib/ownlog -czf "/var/backups/ownlog/files-$(date +%F).tar.gz" inbox
```

恢复数据库用 `pg_restore`。恢复文件时把压缩包解回 `/var/lib/ownlog`，相对路径不要改。S3 上的对象用存储服务自己的版本或复制策略，不在这个进程里做。

令牌明文不在库里。备份能恢复哈希和日志，不能把已经发出去的 `olk_` 再变回明文。丢了明文就重新 `create_agent_token`，再把旧的 `revoked_at` 填上。
