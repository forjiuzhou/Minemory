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
- 不监听网络，不把控机接到 `ownlog-mcp`。远程令牌读的是已经记下的事件，不能操作手机。

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
