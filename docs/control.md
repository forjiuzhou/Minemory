# 本机控机

自记的无障碍服务原来只观察：界面文字、点击、截屏。现在它也可以操作这台手机。采集还是主体，控机是加上去的。

包名仍是 `dev.owner.ownlog`。

## 开关

设置 → 操作 → **允许操作手机**。

默认**开**。没存过这个偏好时也是开，包括升级上来的旧安装。设置里可以关掉。关掉之后，试用页的按钮和下面的 Binder 都直接返回「控机已关闭」，不会去点屏幕。使用情况、通知、位置、收件箱、`ui_text`、`tap` 都不受这个开关影响。

系统无障碍里的「自记」必须开着，否则没有服务可调用。控机不另要权限，手势能力写在 `app/src/main/res/xml/accessibility_service_config.xml` 的 `canPerformGestures`。升级后如果滑动没反应，到系统无障碍里把自记关掉再打开一次，让系统重新读这份配置。

## 能做什么

| 动作 | 怎么做 |
| --- | --- |
| 按文字点 | `AccessibilityNodeInfo.performAction(ACTION_CLICK)`。点不到时，才用 `GestureDescription` 点这个节点的中心 |
| 按 view id 点 | `findAccessibilityNodeInfosByViewId`，id 必须是 `包名:id/名字` |
| 滑动 | 屏幕中部的 `GestureDescription` 笔画，大约 320 毫秒 |
| 返回 / 主页 / 最近任务 | `performGlobalAction` |
| 写入文字 | 只对当前焦点上的可编辑节点 `ACTION_SET_TEXT` |

多个相同文字时，优先当前应用窗口，并且更偏向可点的那一个。自记自己的控件会跳过。

试用页在设置里的「试试操作」。一行命令：

```text
tap 搜索
tap id com.android.settings:id/search
点击id com.android.settings:id/search
back
home
recents
swipe up
上滑
type 你好
输入 你好
```

点击、输入、滑动会先把自记退到后台，再执行。返回、主页、最近任务立刻执行。

## 不会做什么

- **不往密码框写。** 节点或它的直接父节点是 `isPassword`，或 `inputType` 属于密码变体，就拒绝。不会改写旁边另一个框，也不用剪贴板去绕。Android 13 以下拿不到 `inputType`，只能看 `isPassword`。应用没标明的密码框拦不住，采集那边也是这条限制。
- 不点自记自己的控件，不往自记自己的输入框写。
- 控机命令不另写一条 `events`。深度模式开着时，写进非密码输入框的字仍可能被原来的 `ui_text` 记下。
- 不监听端口，不导出 Binder。远程操作走下面这一节，不走 ADB。

## 进程内入口

界面和 Binder 都走 `dev.owner.ownlog.service.ControlBridge`。

`ControlBridge.executeLine(context, line)` 解析一行命令。解析和「能不能写」在 `dev.owner.ownlog.domain.ControlCommand`，不依赖 Android，单元测试在 `app/src/test/java/dev/owner/ownlog/ControlTest.kt`。真正动作在 `AccessibilityActuator`，由 `OwnAccessibilityService.runControl` 调用。服务会再看一次开关。

## Binder

给以后同一台手机上的伴侣进程用。当前没有第二个进程。

- 类：`dev.owner.ownlog.service.ControlBinderService`
- `android:exported="false"`，没有 intent-filter
- 显式 Intent：`Intent(context, ControlBinderService::class.java)`
- `onBind` 再核对调用方 UID 是不是本应用。对不上就返回 null

协议是 `Messenger`：

| 字段 | 值 |
| --- | --- |
| 请求 `what` | `ControlBinderService.MSG_RUN`（1） |
| 请求 Bundle | `line`：一行命令 |
| 回复 `what` | `ControlBinderService.MSG_RESULT`（2） |
| 回复 Bundle | `result`：`ok\t…` 或 `refused\t…` |

`result` 用 `ControlResult.parseWire` 解。失败原因是给人看的短句，例如「拒绝写入密码框」「无障碍未连接」「控机已关闭」。写入成功时不把文字本身再放进回复。

不要把这个服务改成导出，也不要在它前面加一个端口。伴侣如果要做，留在同一个 UID 里 bind。

## 远程控机

外部代理不连手机。它调用 `ownlog-mcp` 的 `phone_*` 工具，边缘函数用 service role 往 `phone_commands` 插一行。手机用**已经登录的用户 access token**（和上传事件同一把）去领，执行完把结果写回去。APK 里没有 service role。

没有 Realtime 客户端，所以无障碍连上并且「允许远程控制」开着时，自记起一个前台服务，大约每 1.5 秒轮询一次。Android 15 对 `dataSync` 前台服务有每日时长限制，这里用的是 `specialUse`，避免控机会话被系统按时长掐掉。轮询循环不绑在通知服务的销毁上：系统如果只收走通知，循环还在，通知要等无障碍重连或设置再变一次才会回来。关掉远程，或无障碍断开，循环才停。

### 开关

设置 → 操作 → **允许远程控制**。

没存过这个偏好时默认**开**，但「允许操作手机」关着时远程不会执行，开关也不可点。关掉远程之后，本机「试试操作」不受影响。

系统无障碍里的「自记」必须开着。自记要已登录。心跳写在 `phone_presence`。超过 20 秒没有心跳，MCP 视为不在线，不会把命令丢进去干等。

前台通知写着「自记可以远程操作」。这是提醒，不是另一条权限。

### 命令

`phone_commands.action`：`tap`、`swipe`、`back`、`home`、`recents`、`type`、`open_app`、`dump_ui`、`wait`。

`args` 示例：

```json
{"text":"搜索"}
{"view_id":"com.xingin.xhs:id/search"}
{"direction":"up"}
{"x1":100,"y1":1600,"x2":100,"y2":600,"duration_ms":320}
{"text":"你好"}
{"name":"小红书"}
{"package":"com.xingin.xhs"}
{"ms":800}
```

`open_app` 认识微信（`com.tencent.mm`）、小红书（`com.xingin.xhs`）、拼多多（`com.xunmeng.pinduoduo`）。其他应用按已安装的显示名或包名找。不会打开自记自己。

领取是一条更新：`status` 从 `pending` 到 `running`，并且还没过 `expires_at`。对不上就放弃，避免两台手机各执行一次。MVP 一个用户一台手机；`device_id` 为空表示这台已登录的手机可以领。

`pending` 默认 2 分钟过期。手机把过期的改成 `cancelled`。卡住超过 60 秒的 `running` 改成 `failed`。

结果写在 `result`：

```json
{
  "ok": true,
  "message": "已点击「搜索」",
  "error": null,
  "foreground_package": "com.xingin.xhs",
  "ui_tree": "package=com.xingin.xhs\nd=3 pkg=com.xingin.xhs text=\"搜索\" desc=\"\" id= clickable=1 bounds=0,100,200,180",
  "ui_truncated": false,
  "duration_ms": 900
}
```

点、滑、返回、打开应用、输入、等待之后都会附上界面树，所以代理不用再单独要一次也能看见新画面。`dump_ui` 只读树。树里是包名、文字、contentDescription、resource-id、是否可点、bounds。密码框的文字和说明留空，并标 `password=1`。自记自己的节点标 `own=1`，点击和输入仍会拒绝。单份树大约停在 80KB、400 个节点。不存截图。

这些命令不写入 `events`。本机 logcat 标签 `OwnLogRemote` 会留一行动作和成败。

密码框仍然拒绝写入，规则和本机控机相同。

表、RLS 和 `can_control` 在 `supabase/migrations/20261102180000_phone_control.sql`。代理怎么调用见 [mcp.md](mcp.md)。
