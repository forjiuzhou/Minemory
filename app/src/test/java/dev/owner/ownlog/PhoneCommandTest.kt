package dev.owner.ownlog

import dev.owner.ownlog.domain.AppChoice
import dev.owner.ownlog.domain.ControlCommand
import dev.owner.ownlog.domain.ControlPolicy
import dev.owner.ownlog.domain.InstalledApp
import dev.owner.ownlog.domain.JsonValue
import dev.owner.ownlog.domain.PhoneApps
import dev.owner.ownlog.domain.PhoneArgBag
import dev.owner.ownlog.domain.PhoneClaim
import dev.owner.ownlog.domain.PhoneCommands
import dev.owner.ownlog.domain.PhonePlan
import dev.owner.ownlog.domain.PhonePlanResult
import dev.owner.ownlog.domain.PhoneResults
import dev.owner.ownlog.domain.SwipeDirection
import dev.owner.ownlog.domain.UiTreeNode
import dev.owner.ownlog.domain.UiTreeText
import dev.owner.ownlog.domain.parseJson
import dev.owner.ownlog.domain.parseRemoteCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCommandTest {
    @Test
    fun remoteDefaultsOnOnlyWhileLocalControlIsOn() {
        assertTrue(ControlPolicy.REMOTE_ENABLED_BY_DEFAULT)
        assertTrue(ControlPolicy.remoteEnabled(null, controlEnabled = true))
        assertFalse(ControlPolicy.remoteEnabled(null, controlEnabled = false))
        assertFalse(ControlPolicy.remoteEnabled(false, controlEnabled = true))
        assertTrue(ControlPolicy.remoteEnabled(true, controlEnabled = true))
        assertFalse(ControlPolicy.remoteEnabled(true, controlEnabled = false))
    }

    @Test
    fun parsesRemoteActionsAndRefusesAmbiguousTaps() {
        val tap = PhoneCommands.interpret("tap", PhoneArgBag.parse("""{"text":"搜索"}"""))
        val ready = tap as PhonePlanResult.Ready
        assertEquals(ControlCommand.TapText("搜索"), (ready.plan as PhonePlan.Act).command)

        val byId = PhoneCommands.interpret(
            "tap",
            PhoneArgBag.parse("""{"view_id":"com.xingin.xhs:id/search"}"""),
        ) as PhonePlanResult.Ready
        assertEquals(
            ControlCommand.TapId("com.xingin.xhs:id/search"),
            (byId.plan as PhonePlan.Act).command,
        )

        val both = PhoneCommands.interpret(
            "tap",
            PhoneArgBag(text = "搜索", viewId = "com.xingin.xhs:id/search"),
        ) as PhonePlanResult.Invalid
        assertEquals("tap 只能给文字或 view id 其中一个", both.message)

        val swipe = PhoneCommands.interpret("swipe", PhoneArgBag(direction = "up")) as PhonePlanResult.Ready
        assertEquals(ControlCommand.Swipe(SwipeDirection.UP), (swipe.plan as PhonePlan.Act).command)

        val path = PhoneCommands.interpret(
            "swipe",
            PhoneArgBag(x1 = 10.0, y1 = 20.0, x2 = 10.0, y2 = 400.0),
        ) as PhonePlanResult.Ready
        val stroke = (path.plan as PhonePlan.Act).command as ControlCommand.SwipePath
        assertEquals(400f, stroke.y2)

        val open = PhoneCommands.interpret("open_app", PhoneArgBag(name = "小红书")) as PhonePlanResult.Ready
        assertEquals("小红书", (open.plan as PhonePlan.Open).label)

        val waited = PhoneCommands.interpret("wait", PhoneArgBag(ms = 500)) as PhonePlanResult.Ready
        assertEquals(ControlCommand.Wait(500), (waited.plan as PhonePlan.Act).command)
        assertTrue(PhoneCommands.interpret("wait", PhoneArgBag(ms = 0)) is PhonePlanResult.Invalid)
        assertTrue(PhoneCommands.interpret("type", PhoneArgBag(text = "")) is PhonePlanResult.Invalid)
        assertEquals(ControlCommand.DumpUi, ((PhoneCommands.interpret("dump_ui", PhoneArgBag()) as PhonePlanResult.Ready).plan as PhonePlan.Act).command)
    }

    @Test
    fun claimSkipsExpiredAndOtherDevices() {
        val now = 1_000_000L
        assertTrue(PhoneClaim.acceptable("pending", now + 1, now, null, "device-a"))
        assertFalse(PhoneClaim.acceptable("running", now + 1, now, null, "device-a"))
        assertFalse(PhoneClaim.acceptable("pending", now, now, null, "device-a"))
        assertFalse(PhoneClaim.acceptable("pending", now + 1, now, "device-b", "device-a"))
        assertTrue(PhoneClaim.acceptable("pending", now + 1, now, "device-a", "device-a"))
        assertFalse(PhoneClaim.runningIsStale(now, now + 1_000))
        assertTrue(PhoneClaim.runningIsStale(now, now + PhoneClaim.RUNNING_STALE_MS))
        assertTrue(PhoneClaim.runningIsStale(null, now))
    }

    @Test
    fun readsPendingRowsFromPostgrest() {
        val rows = parseRemoteCommands(
            """[{"id":"11111111-1111-4111-8111-111111111111","action":"open_app","args":{"name":"微信"},"device_id":null,"status":"pending","expires_at":"2026-11-02T00:02:00Z"}]""",
        )
        assertEquals(1, rows.size)
        assertEquals("open_app", rows[0].action)
        assertEquals("微信", rows[0].args.name)
        assertNull(rows[0].deviceId)
        assertTrue(rows[0].expiresAtMillis > 1_700_000_000_000L)
    }

    @Test
    fun uiTreeRedactsPasswordsAndTruncates() {
        val secret = UiTreeText.render(
            UiTreeNode(
                packageName = "com.example",
                text = "p@ss",
                contentDescription = "hidden",
                viewId = "com.example:id/password",
                clickable = false,
                password = true,
                ownApp = false,
                bounds = "1,2,3,4",
                depth = 2,
            ),
        )
        assertFalse(secret.contains("p@ss"))
        assertFalse(secret.contains("hidden"))
        assertTrue(secret.contains("password=1"))

        val nodes = List(UiTreeText.MAX_NODES + 5) { index ->
            UiTreeNode(
                packageName = "com.xingin.xhs",
                text = "笔记$index",
                contentDescription = null,
                viewId = null,
                clickable = true,
                password = false,
                ownApp = false,
                bounds = "0,0,10,10",
                depth = 1,
            )
        }
        val dump = UiTreeText.format("com.xingin.xhs", nodes)
        assertTrue(dump.truncated)
        assertEquals(UiTreeText.MAX_NODES, dump.nodeCount)
        assertTrue(dump.text.startsWith("package=com.xingin.xhs"))
    }

    @Test
    fun knownAppsResolveAndOwnAppIsRefused() {
        assertEquals("com.xingin.xhs", PhoneApps.aliasPackage("小红书"))
        assertEquals("com.xunmeng.pinduoduo", PhoneApps.aliasPackage("拼多多"))
        assertEquals("com.tencent.mm", PhoneApps.aliasPackage("WeChat"))
        assertEquals(
            AppChoice.Rejected("不会打开自记自己"),
            PhoneApps.choose("小红书", emptyList(), "com.xingin.xhs"),
        )
        val chosen = PhoneApps.choose(
            "备忘",
            listOf(
                InstalledApp("dev.owner.ownlog", "自记"),
                InstalledApp("com.notes", "备忘录"),
            ),
            "dev.owner.ownlog",
        )
        assertEquals(AppChoice.One("com.notes"), chosen)
        assertEquals("不会打开自记自己", PhoneApps.rejectPackage("dev.owner.ownlog", "dev.owner.ownlog"))
        assertEquals("包名不像 Android 包名", PhoneApps.rejectPackage("not a package", "dev.owner.ownlog"))
    }

    @Test
    fun resultJsonDoesNotEchoTypedText() {
        val raw = PhoneResults.json(
            ok = false,
            message = "拒绝写入密码框",
            error = "拒绝写入密码框",
            foregroundPackage = "com.tencent.mm",
            uiTree = "package=com.tencent.mm",
            uiTruncated = false,
            durationMs = 12,
        )
        assertFalse(raw.contains("secret-password"))
        val parsed = parseJson(raw) as JsonValue.Obj
        assertEquals(JsonValue.Bool(false), parsed.fields["ok"])
        assertEquals(JsonValue.Str("拒绝写入密码框"), parsed.fields["error"])
        assertEquals(JsonValue.Str("com.tencent.mm"), parsed.fields["foreground_package"])
    }
}
