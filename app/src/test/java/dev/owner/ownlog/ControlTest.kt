package dev.owner.ownlog

import dev.owner.ownlog.domain.ControlCommand
import dev.owner.ownlog.domain.ControlCommands
import dev.owner.ownlog.domain.ControlGeometry
import dev.owner.ownlog.domain.ControlNode
import dev.owner.ownlog.domain.ControlPolicy
import dev.owner.ownlog.domain.ControlResult
import dev.owner.ownlog.domain.ControlTargeting
import dev.owner.ownlog.domain.PasswordSignals
import dev.owner.ownlog.domain.SwipeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlTest {
    @Test
    fun controlDefaultsOnWhenUnset() {
        assertTrue(ControlPolicy.ENABLED_BY_DEFAULT)
        assertTrue(ControlPolicy.controlEnabled(null))
        assertTrue(ControlPolicy.controlEnabled(true))
        assertFalse(ControlPolicy.controlEnabled(false))
    }

    @Test
    fun parsesOneLineCommands() {
        assertEquals(ControlCommand.Back, ControlCommands.parse("  BACK "))
        assertEquals(ControlCommand.Back, ControlCommands.parse("返回"))
        assertEquals(ControlCommand.Home, ControlCommands.parse("home"))
        assertEquals(ControlCommand.Home, ControlCommands.parse("主页"))
        assertEquals(ControlCommand.Recents, ControlCommands.parse("最近任务"))
        assertEquals(ControlCommand.Swipe(SwipeDirection.UP), ControlCommands.parse("swipe up"))
        assertEquals(ControlCommand.Swipe(SwipeDirection.DOWN), ControlCommands.parse("下滑"))
        assertEquals(ControlCommand.Swipe(SwipeDirection.LEFT), ControlCommands.parse("swipe left"))
        assertEquals(ControlCommand.Swipe(SwipeDirection.RIGHT), ControlCommands.parse("右滑"))
        assertEquals(ControlCommand.TapText("搜索"), ControlCommands.parse("tap   搜索"))
        assertEquals(ControlCommand.TapText("确定 按钮"), ControlCommands.parse("点击 text 确定 按钮"))
        assertEquals(
            ControlCommand.TapId("com.android.settings:id/search"),
            ControlCommands.parse("tap id com.android.settings:id/search"),
        )
        assertEquals(
            ControlCommand.TapId("com.android.settings:id/search"),
            ControlCommands.parse("点击id com.android.settings:id/search"),
        )
        assertEquals(ControlCommand.SetText("你好 世界"), ControlCommands.parse("type 你好 世界"))
        assertEquals(ControlCommand.SetText("你好"), ControlCommands.parse("输入 你好"))
        assertNull(ControlCommands.parse(""))
        assertNull(ControlCommands.parse("tap"))
        assertNull(ControlCommands.parse("type"))
        assertNull(ControlCommands.parse("back now"))
        assertNull(ControlCommands.parse("nope"))
    }

    @Test
    fun tapAndTypeKnowWhenToLeaveThisApp() {
        assertFalse(ControlCommand.Back.backgroundsFirst)
        assertFalse(ControlCommand.Home.backgroundsFirst)
        assertTrue(ControlCommand.TapText("搜索").backgroundsFirst)
        assertTrue(ControlCommand.SetText("你好").backgroundsFirst)
        assertTrue(ControlCommand.Swipe(SwipeDirection.UP).backgroundsFirst)
    }

    @Test
    fun rejectsBadTargetsBeforeAnyAction() {
        val longText = "字".repeat(ControlPolicy.MAX_SET_TEXT + 1)
        assertEquals("文字太长", ControlPolicy.reject(ControlCommand.SetText(longText))?.message)
        assertNull(ControlPolicy.reject(ControlCommand.SetText("你好")))
        assertEquals(
            "view id 要写成 包名:id/名字",
            ControlPolicy.reject(ControlCommand.TapId("search"))?.message,
        )
        assertNull(ControlPolicy.reject(ControlCommand.TapId("com.android.settings:id/search")))
        assertFalse(ControlPolicy.isViewId("com.foo:id/has space"))
    }

    @Test
    fun refusesPasswordAndUnfocusedTextTargets() {
        assertEquals("没有焦点输入框", ControlTargeting.decideSetText(null).message)
        assertFalse(ControlTargeting.decideSetText(null).ok)
        val password = node(password = true, editable = true, focused = true)
        val refused = ControlTargeting.decideSetText(password)
        assertFalse(refused.ok)
        assertEquals("拒绝写入密码框", refused.message)
        assertEquals(
            "拒绝写入密码框",
            ControlTargeting.decideSetText(node(password = true, editable = true, focused = true, ownApp = true)).message,
        )
        val plain = node(password = false, editable = true, focused = true, ownApp = false)
        assertTrue(ControlTargeting.decideSetText(plain).ok)
        assertEquals(
            "焦点不在可编辑控件上",
            ControlTargeting.decideSetText(node(editable = false, focused = true)).message,
        )
        assertEquals(
            "不会往自记自己的输入框里写",
            ControlTargeting.decideSetText(node(editable = true, focused = true, ownApp = true)).message,
        )
        val numberPassword = PasswordSignals.TYPE_CLASS_NUMBER or PasswordSignals.TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(PasswordSignals.isPassword(false, numberPassword))
    }

    @Test
    fun choosesVisibleForeignNodeAndKeepsEarlierTie() {
        val nodes = listOf(
            node(text = "搜索", ownApp = true, clickable = true),
            node(text = "打开搜索历史", clickable = true),
            node(text = "搜索", clickable = false),
            node(text = "搜索", clickable = true, enabled = false),
        )
        assertEquals(2, ControlTargeting.bestTapIndex(nodes, "搜索", byId = false))
        val ownOnly = listOf(node(text = "搜索", ownApp = true, clickable = true))
        assertEquals(-1, ControlTargeting.bestTapIndex(ownOnly, "搜索", byId = false))
        assertEquals("只找到自记自己的控件", ControlTargeting.missReason(ownOnly, "搜索", byId = false))
        assertEquals("没找到「没有」", ControlTargeting.missReason(nodes, "没有", byId = false))
        val byId = listOf(
            node(viewId = "com.foo:id/other"),
            node(viewId = "com.android.settings:id/search", visible = false),
            node(viewId = "com.android.settings:id/search"),
        )
        assertEquals(2, ControlTargeting.bestTapIndex(byId, "com.android.settings:id/search", byId = true))
    }

    @Test
    fun swipeMovesTowardTheNamedEdge() {
        val up = ControlGeometry.swipeStroke(SwipeDirection.UP, 1080, 2400)
        assertTrue(up.y1 > up.y2)
        assertEquals(up.x1, up.x2)
        assertEquals(540f, up.x1)
        assertEquals(ControlGeometry.SWIPE_DURATION_MS, up.durationMs)
        val down = ControlGeometry.swipeStroke(SwipeDirection.DOWN, 1080, 2400)
        assertTrue(down.y2 > down.y1)
        val left = ControlGeometry.swipeStroke(SwipeDirection.LEFT, 1080, 2400)
        assertTrue(left.x1 > left.x2)
        val right = ControlGeometry.swipeStroke(SwipeDirection.RIGHT, 1080, 2400)
        assertTrue(right.x2 > right.x1)
        val tiny = ControlGeometry.swipeStroke(SwipeDirection.UP, 20, 20)
        assertTrue(tiny.y1 >= 8f && tiny.y2 >= 8f)
        assertTrue(tiny.y1 <= 12f && tiny.y2 <= 12f)
    }

    @Test
    fun wireRoundTripDoesNotDependOnAndroid() {
        val refused = ControlResult.refused("拒绝写入密码框")
        assertEquals(refused, ControlResult.parseWire(refused.wire()))
        val ok = ControlResult.ok("已返回")
        assertEquals(ok, ControlResult.parseWire(ok.wire()))
    }

    private fun node(
        text: String? = null,
        contentDescription: String? = null,
        viewId: String? = null,
        password: Boolean = false,
        editable: Boolean = false,
        focused: Boolean = false,
        clickable: Boolean = false,
        enabled: Boolean = true,
        visible: Boolean = true,
        ownApp: Boolean = false,
    ) = ControlNode(
        text = text,
        contentDescription = contentDescription,
        viewId = viewId,
        password = password,
        editable = editable,
        focused = focused,
        clickable = clickable,
        enabled = enabled,
        visible = visible,
        ownApp = ownApp,
    )
}
