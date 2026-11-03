# 给代理用的查询

先把会话时区设成你平时所在的时区。`occurred_at` 是 `timestamptz`，不设时区的话「昨天下午」会对不齐。

```sql
set time zone 'Asia/Shanghai';
```

手机只写入 `events`。本地队列上传成功后，同一条不会再插一次（主键是客户端生成的 uuid）。多台设备时加上 `device_id` 条件。设备 ID 显示在自记首页。

用 Supabase 的管理连接器（service role 这一层）来查。它绕过 RLS。手机里的 publishable / anon key 自己读不了这些行，必须再加上登录用户的 access token，而且只能读到 `user_id = auth.uid()` 的记录。不要把手机里的钥匙当成代理的查询凭证。

`user_id` 是登录用户。手机插入时会带上，数据库默认值也是 `auth.uid()`。用管理连接器查询时不需要自己套 `auth.uid()`。

## 昨天下午各应用用了多久

时长在 `app_bg.extras.duration_ms`，单位毫秒。`app_fg` / `app_bg` 用 `extras.session_id` 配成一对。进程被系统杀掉时，补上的离开事件会带 `extras.interrupted = true`。

```sql
select
    package_name,
    app_label,
    round(sum((extras->>'duration_ms')::bigint) / 60000.0, 1) as minutes
from events
where type = 'app_bg'
  and occurred_at >= date_trunc('day', now() - interval '1 day') + interval '12 hours'
  and occurred_at <  date_trunc('day', now() - interval '1 day') + interval '18 hours'
group by package_name, app_label
order by minutes desc nulls last;
```

## 微信里读过的公众号文章

文章也常在 `share`：`content` 是分享原文，`extras.url` 是链接。深度模式默认开着，界面文字和截屏识别都会记。微信视频号在默认识别名单里（`com.tencent.mm`）。视频号页会收成一条 `ui_text`，`extras.screen_hint` 为 `wechat_channels`，正文是标题、作者和描述，不是一堆碎片。画在画面上、无障碍读不到的字在 `screen_ocr`。和上一条相似度超过 90% 的识别结果不会再记一条。

```sql
select occurred_at, title, left(content, 400) as content
from events
where package_name = 'com.tencent.mm'
  and type in ('ui_text', 'screen_ocr')
  and occurred_at > now() - interval '7 days'
order by occurred_at desc
limit 200;
```

## 抖音看过的视频

应用进出在 `app_fg` / `app_bg`（使用情况访问）。分享在 `share`。深度模式默认开，截屏名单里有 `com.ss.android.ugc.aweme`、`com.ss.android.ugc.aweme.lite`、`com.xingin.xhs` 和 `com.tencent.mm`。作者、文案在 `content`。`extras.reason` 为 `ocr_list`、`no_a11y_text` 或 `interaction`，`extras.capture_method` 为 `media_projection` 或 `accessibility`。`interaction` 是名单应用在点击约 1 秒后、或滑动停稳约 0.8 秒再等 1 秒后的截屏，同一应用默认至少隔 5 秒（可配 3–60 秒），不受全局 20 秒限制。名单心跳和没有无障碍文字的补截仍至少隔 20 秒。同一包名下和上一条已保存文字超过 90% 相似的识别会丢掉。

```sql
select
    occurred_at,
    type,
    title,
    left(content, 500) as content,
    extras->>'capture_method' as capture_method,
    extras->>'confidence' as confidence
from events
where package_name in ('com.ss.android.ugc.aweme', 'com.ss.android.ugc.aweme.lite')
  and type in ('ui_text', 'screen_ocr', 'tap')
  and occurred_at > now() - interval '2 days'
order by occurred_at desc
limit 300;
```

小红书笔记详情是一条 `ui_text`，`extras.screen_hint = 'xhs_note'`。包名 `com.xingin.xhs`。

```sql
select occurred_at, title, content, extras->>'author' as author
from events
where extras->>'screen_hint' in ('xhs_note', 'wechat_channels')
  and occurred_at > now() - interval '2 days'
order by occurred_at desc
limit 100;
```

## 和花钱有关的通知

支付宝、微信收款、短信验证码都会留下。这是按你的要求记的，不是漏网。

```sql
select occurred_at, package_name, app_label, title, content
from events
where type = 'notification'
  and occurred_at > now() - interval '30 days'
  and (
        coalesce(title, '') ~ '(支付|收款|转账|账单|消费|扣款|付款|退款|验证码|支付宝)'
     or coalesce(content, '') ~ '(支付|收款|转账|账单|消费|扣款|付款|退款|验证码|¥|￥)'
  )
order by occurred_at desc;
```

## 去过的地方

移动大约 100 米记一条，停着大约每 8 分钟记一条。`extras` 里是 `lat`、`lng`、`accuracy_m`。

```sql
select
    occurred_at,
    (extras->>'lat')::double precision as lat,
    (extras->>'lng')::double precision as lng,
    (extras->>'accuracy_m')::double precision as accuracy_m
from events
where type = 'location'
  and occurred_at > now() - interval '1 day'
order by occurred_at;
```

把接近的点收成一小块：

```sql
select
    round((extras->>'lat')::numeric, 3) as lat,
    round((extras->>'lng')::numeric, 3) as lng,
    count(*) as fixes,
    min(occurred_at) as first_seen,
    max(occurred_at) as last_seen
from events
where type = 'location'
  and occurred_at > now() - interval '7 days'
group by 1, 2
order by last_seen desc;
```

## 赞过、收藏过、评论过、转发过

类型是 `engagement`。手机在深度模式里记下按钮，不记整段私聊，也不记密码框。`extras.action` 是 `like`（赞 / 喜欢）、`favorite`（收藏）、`comment`（评论）、`share`（分享或转发）。作者在 `extras.author`，屏幕上能看见的链接在 `extras.url`。没有弹出分享面板、或者链接只在系统分享单里时，`url` 是空的。私聊里只留动作和窗口标题（通常是对方名字），`extras.private_context` 为 true，正文不是消息内容。

先确认已经跑过 [supabase/migrations/20261103140000_engagement.sql](../supabase/migrations/20261103140000_engagement.sql)。否则 `type = 'engagement'` 插不进去。

```sql
select
    occurred_at,
    package_name,
    app_label,
    extras->>'action' as action,
    extras->>'action_label' as action_label,
    title,
    extras->>'author' as author,
    extras->>'url' as url,
    left(content, 240) as content
from events
where type = 'engagement'
  and occurred_at > now() - interval '14 days'
order by occurred_at desc
limit 100;
```

只要某一类，例如收藏：

```sql
select occurred_at, package_name, app_label, title, extras->>'author' as author, extras->>'url' as url
from events
where type = 'engagement'
  and extras->>'action' = 'favorite'
  and occurred_at > now() - interval '30 days'
order by occurred_at desc;
```

小红书、抖音、微信、哔哩哔哩的包名分别是 `com.xingin.xhs`、`com.ss.android.ugc.aweme`（极速版 `com.ss.android.ugc.aweme.lite`）、`com.tencent.mm`、`tv.danmaku.bili`。

MCP 不用新工具。`search` 或 `timeline` 把 `types` 设为 `["engagement"]`。令牌范围是 `{*}` 时本来就能读到。搜索打在标题和正文上（正文开头是「赞」「收藏」「评论」「分享」或「转发」），按动作过滤用上面的 SQL，或在返回的 `extras.action` 上筛。

## 某次点击点了什么

不记坐标。密码框只有 `title = '密码框'`，`extras.password_skipped = true`，没有密码文本。

```sql
select occurred_at, package_name, app_label, title, content, extras
from events
where type = 'tap'
  and occurred_at > now() - interval '1 day'
order by occurred_at desc
limit 200;
```

## 收件箱里还没整理的

手机不整理。`organized_at is null` 的 `note` / `photo` / `voice` / `share` 就是待整理的收件。做法见 [organize-skill.md](organize-skill.md)。

```sql
select id, occurred_at, type, title, left(content, 400) as content, extras
from events
where type in ('note', 'photo', 'voice', 'share')
  and organized_at is null
order by occurred_at desc
limit 100;
```

分享来的链接在 `extras.url`，来源包名在 `extras.source_package`。文件在私有桶 `inbox`，对象键是 `extras.file_path`，形状是 `<user_id>/<yyyy>/<mm>/<uuid>.<ext>`。用管理连接器下载：

```http
GET https://PROJECT.supabase.co/storage/v1/object/inbox/{file_path}
```

连接器自带 service-level 凭证，绕过 RLS。不要用手机里的 publishable key 去下这些文件。

写回整理结果时只改这三列：

```sql
update events
set tags = array['article', 'link:reading-2026-10'],
    summary = '一句人话摘要',
    organized_at = now()
where id = '00000000-0000-0000-0000-000000000000'
  and organized_at is null;
```

