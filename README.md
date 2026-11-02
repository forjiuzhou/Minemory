# 自记 OwnLog

产品名：自记 / Minemory；Android 包与代码名：OwnLog（`dev.owner.ownlog`）。

装在你自己手机上的收件箱。侧载使用，不进 Play 商店。它只负责尽快记下，然后把原文存进你自己的 Supabase。应用里没有整理、没有摘要、也不跑模型。之后由外部代理来归类，做法见 [docs/organize-skill.md](docs/organize-skill.md)。

密码框里的文字不记。支付、转账金额、商户、账单、验证码通知会记。

## 默认记下什么

| type | 内容 |
| --- | --- |
| `note` | 首页写下的文字。可选备注在 `extras.note` |
| `photo` | 相册或相机。`content` 是本机中文 OCR，原图上传到 Storage |
| `voice` | 按住说话的 AAC（m4a）。本机有中文离线识别时写入 `content`，否则 `extras.transcribed` 为 false |
| `share` | 从别的应用分享过来的文字、链接、图片或文件。链接在 `extras.url`，全文留在 `content` |
| `app_fg` / `app_bg` | 使用情况访问读到的前台会话。离开时 `extras.duration_ms` 是这次时长，`extras.source` 为 `usage_stats` |
| `notification` | 应用、标题、正文、时间。短信验证码在这里 |
| `location` | 大约移动 100 米记一条；停着大约每 8 分钟记一条 |

深度模式默认开。新安装，以及从来没手动关过它的旧安装，都会记 `ui_text`、`tap` 和 `screen_ocr`。第一次打开会带你打开无障碍并授权截屏。截屏授权在重启后会失效，通知栏有一条常驻提醒，点「重新授权」即可。首页也会出现同样的入口。

自记自己的界面不记。没有应用黑名单。1.5 秒内的进出闪一下会被收成同一次会话。

代理查询见 [docs/agent-queries.md](docs/agent-queries.md)。远程 MCP 见 [docs/mcp.md](docs/mcp.md)，自建见 [docs/self-host.md](docs/self-host.md)。

## 构建

需要 JDK 17 或更高，以及 Android SDK 35（compileSdk / targetSdk 35）。

```bash
cp local.properties.example local.properties
# 编辑 sdk.dir。Supabase 可以现在填，也可以等装好后在应用里填。
./gradlew assembleDebug test
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。

这个调试包只打了 `armeabi-v7a` 和 `arm64-v8a`，给手机用。x86 模拟器需要先去掉 `app/build.gradle.kts` 里的 `abiFilters`。中文 OCR 模型打进包里，APK 会比较大，识别不依赖第一次下载模型。

`local.properties` 里可选：

```properties
SUPABASE_URL=https://xxxx.supabase.co
SUPABASE_ANON_KEY=sb_publishable_... 或旧的 anon key（eyJ...）
```

应用里之后填写的值会盖过这两个。`sb_publishable_` 和旧的 anon JWT 都可以。不要把 `sb_secret_` 或 `service_role` 写进手机或这个文件。应用会拒绝保存 `sb_secret_`。

## 安装

1. 把 APK 拷到手机，允许安装未知来源，装上「自记」。
2. 打开应用。首页是收件箱，可以写文字、选照片、拍照、按住说话。右上角进设置。

### 使用情况访问

设置 → 自记 → 采集 → **应用使用** 旁边的按钮，或系统里：设置 → 应用 → 特殊应用权限 → 使用情况访问权限 → 自记 → 允许。

这是默认的应用进出记录，大约每 15 分钟读一次。不靠无障碍。

### 通知

- Android 13 及以上：允许自记发通知。定位开着时系统要求有一条常驻通知。
- 设置 → 应用 → 特殊应用权限 → 设备和应用通知（通知使用权）→ 自记 → 允许。

### 位置

先允许精确位置。自记会拉起一个前台服务，通知栏写着「自记正在记录位置」。服务活着的时候，「仅在使用中允许」通常就够。

定位用 Google Play 服务的融合定位。没有 Play 服务的手机，这一项起不来，其他采集还在。

### 分享

任意应用的分享菜单里选「记到自记」。底部卡片会显示文字或链接、来源，备注可以留空再保存。图片和文件会把原件上传。保存之后才会去抓网页标题，失败不影响已经记下的那条。

### 电池

设置 → 应用 → 自记 → 应用电池用量 → 不受限制。开机后如果定位没自己回来，打开一次自记即可。

### 深度模式（默认开）

第一次打开会按顺序走到这几步。已经开过无障碍、并且截屏还有效时，会直接进收件箱。

1. 允许通知，这样重启后才能看到重新授权的提醒。
2. Android 13 及以上：设置 → 应用 → 自记 → 右上角 ⋮ → **允许受限设置**。
3. 设置 → 无障碍 → 已安装的应用 → 自记 → 打开。它只读窗口，不会替你点屏幕。
4. 授权截屏，选整个屏幕。系统会显示正在录屏的标志。

授权在重启后失效。自记会留一条不能划掉的通知，动作是「重新授权」。打开应用时，首页也会提示。

名单里的应用会在点按后大约 1 秒截屏，原因是 `interaction`。滑动要先停稳约 0.8 秒（这段时间没有新的滑动），再等 1 秒。同一应用两次交互截屏默认至少隔 5 秒，设置里可以改成 3 到 60 秒。全局 20 秒只约束名单心跳，以及没有无障碍文字时的补截，不约束这种交互截屏。息屏不截。不开启触摸探索，也不听原始触摸事件。同一应用里，和上一条已保存的识别文字相似度超过 90% 的结果会丢掉。

设置页的「今天」会显示当天的识别次数、记下的条数，以及大约上传了多少字节。

小红书笔记详情和微信视频号会收成一条 `ui_text`：标题、作者、正文各一行，`extras.screen_hint` 为 `xhs_note` 或 `wechat_channels`。微信聊天仍按普通界面文字记。

默认 OCR 包名：`com.ss.android.ugc.aweme`、`com.ss.android.ugc.aweme.lite`、`com.xingin.xhs`、`com.tencent.mm`。一行一个，可以改。从没改过名单的旧安装会自动加上微信。

## 配置 Supabase

1. 新建一个项目。
2. 在 SQL Editor 里按这个顺序整段执行：
   1. [supabase/migrations/20261022000000_events.sql](supabase/migrations/20261022000000_events.sql)
   2. [supabase/migrations/20261022160000_inbox.sql](supabase/migrations/20261022160000_inbox.sql)
   3. [supabase/migrations/20261022180000_owner_auth.sql](supabase/migrations/20261022180000_owner_auth.sql)
3. 打开 **Authentication → Providers**，确认 Email 可用，并**关掉公开注册**（Disable signups）。不要让别人用这个网址自己开账号。
4. 打开 **Authentication → Users**，点 Add user，填你自己的邮箱和密码，创建这个账号。应用不会替你注册。
5. 打开 Connect，复制 Project URL，以及 publishable key（`sb_publishable_...`）。旧项目的 anon public key（`eyJ...`）也可以。
6. 在自记的设置里填上 URL 和这把钥匙，保存。再在同一页用刚才的邮箱和密码登录。可选的邮箱验证码也走同一个账号，不会新建用户。
7. 点测试连接。成功的话它能读到你自己的 `events`。未登录或登录过期时，上传会停，首页和设置里会写明「上传已暂停」。

登录之后，access token 和 refresh token 放在加密的 SharedPreferences 里。快过期时应用会自己刷新。PostgREST 和 Storage 的每次请求都带 `apikey: <publishable key>`，以及 `Authorization: Bearer <用户的 access token>`。`sb_` 钥匙本身不是 JWT，不会被放进 Bearer。旧的 `eyJ` anon key 只在调用登录接口时才会同时作为 Bearer；数据请求的 Bearer 始终是用户的 access token。

`events.user_id` 必须等于当前登录用户。匿名钥匙没有任何表权限。Storage 私有桶 `inbox` 的对象路径是 `<用户 id>/<年>/<月>/<uuid>.<扩展名>`，策略只允许第一段等于 `auth.uid()`。

外部代理用 Supabase 的管理连接器（service role，绕过 RLS）。不要把手机里的 publishable key 交给代理，也不要把 `sb_secret_` 放进手机。代理自己插入新行时要写上 `user_id`，因为 service role 下 `auth.uid()` 是空的，而这一列不能为空。

上传默认走任意网络。设置里可以改成只在 Wi-Fi 上传。没网或未登录时记录留在本机，登录并恢复网络后 WorkManager 会续传。同一条用 uuid 主键，重复上传会被忽略。已上传的本机记录大约 14 天后删掉，Supabase 里的不删。

## 需要真机才能确认的事

单元测试覆盖密码跳过、界面去重、点击过滤、定位节流、OCR 触发、上传分批、分享解析、收件映射和登录请求头。下面这些要在手机上看：

- 使用情况访问授权之后，进出别的应用会在下一轮轮询里出现。
- 分享过来的图片，OCR 和原文件上传是先后两步，原文件在上传成功后才从手机删掉。
- 语音识别取决于系统有没有中文离线包。没有的话音频仍会上传，`extras.transcribed` 为 false。
- 银行或支付页如果设了 `FLAG_SECURE`，系统不允许截屏。深度模式开着时，能读到的无障碍文字仍会记。自记不会绕过这个限制。
- 输入框没有标成密码、但实际上是密码的应用，文字仍会被记下来。自记只跳过 `isPassword` 或密码类 `inputType`。
- 调试包没有混淆，publishable key 可以从 APK 里取出。它单独读不了数据。账号密码不要写进包里。
