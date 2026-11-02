package dev.owner.ownlog.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.owner.ownlog.domain.ControlCommand
import dev.owner.ownlog.domain.ControlGeometry
import dev.owner.ownlog.domain.ControlNode
import dev.owner.ownlog.domain.ControlPolicy
import dev.owner.ownlog.domain.ControlResult
import dev.owner.ownlog.domain.ControlTargeting
import dev.owner.ownlog.domain.PasswordSignals
import dev.owner.ownlog.domain.SwipeDirection
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Performs one control command on the live accessibility tree.
 * Password fields are refused before [AccessibilityNodeInfo.ACTION_SET_TEXT].
 * Clicks prefer [AccessibilityNodeInfo.performAction]; a short gesture is only
 * the fallback when the node does not accept the click.
 */
internal class AccessibilityActuator(
    private val service: OwnAccessibilityService,
) {
    suspend fun run(command: ControlCommand): ControlResult = withContext(Dispatchers.Main) {
        ControlPolicy.reject(command)?.let { return@withContext it }
        when (command) {
            ControlCommand.Back -> global(AccessibilityService.GLOBAL_ACTION_BACK, "已返回", "返回")
            ControlCommand.Home -> global(AccessibilityService.GLOBAL_ACTION_HOME, "已回到主页", "回到主页")
            ControlCommand.Recents -> global(AccessibilityService.GLOBAL_ACTION_RECENTS, "已打开最近任务", "打开最近任务")
            is ControlCommand.Swipe -> swipe(command.direction)
            is ControlCommand.TapText -> tap(command.text, byId = false)
            is ControlCommand.TapId -> tap(command.viewId, byId = true)
            is ControlCommand.SetText -> setText(command.text)
        }
    }

    private fun global(action: Int, okMessage: String, failLabel: String): ControlResult {
        val ok = try {
            service.performGlobalAction(action)
        } catch (_: Exception) {
            false
        }
        return if (ok) ControlResult.ok(okMessage) else ControlResult.refused("系统拒绝了$failLabel")
    }

    private suspend fun swipe(direction: SwipeDirection): ControlResult {
        val (width, height) = screenSize()
        if (width < 16 || height < 16) return ControlResult.refused("读不到屏幕尺寸")
        val stroke = ControlGeometry.swipeStroke(direction, width, height)
        val ok = dispatchStroke(stroke.x1, stroke.y1, stroke.x2, stroke.y2, stroke.durationMs)
        val name = when (direction) {
            SwipeDirection.UP -> "上滑"
            SwipeDirection.DOWN -> "下滑"
            SwipeDirection.LEFT -> "左滑"
            SwipeDirection.RIGHT -> "右滑"
        }
        return if (ok) ControlResult.ok("已$name") else ControlResult.refused(gestureRefused())
    }

    private suspend fun tap(query: String, byId: Boolean): ControlResult {
        val roots = snapshotRoots()
        val found = ArrayList<AccessibilityNodeInfo>(8)
        try {
            collectMatches(roots.nodes, query, byId, found)
            val facts = found.map { toFacts(it) }
            val index = ControlTargeting.bestTapIndex(facts, query, byId)
            if (index < 0) return ControlResult.refused(ControlTargeting.missReason(facts, query, byId))
            val node = found[index]
            if (clickNode(node)) return ControlResult.ok(tapMessage(query, byId))
            if (tapGesture(node)) return ControlResult.ok(tapMessage(query, byId))
            return ControlResult.refused("点不到「${query.take(40)}」")
        } finally {
            found.forEach { node ->
                if (roots.nodes.none { it === node }) recycle(node)
            }
            roots.close()
        }
    }

    private fun tapMessage(query: String, byId: Boolean): String {
        return if (byId) "已点击 ${query.take(80)}" else "已点击「${query.take(40)}」"
    }

    private suspend fun tapGesture(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (_: Exception) {
            return false
        }
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val x = rect.exactCenterX()
        val y = rect.exactCenterY()
        val (width, _) = screenSize()
        val x2 = if (x + 1f < width) x + 1f else x - 1f
        return dispatchStroke(x, y, x2, y, TAP_DURATION_MS)
    }

    private fun setText(text: String): ControlResult {
        val roots = snapshotRoots()
        var focused: AccessibilityNodeInfo? = null
        try {
            for (root in roots.nodes) {
                focused = try {
                    root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                } catch (_: Exception) {
                    null
                }
                if (focused != null) break
            }
            val node = focused ?: return ControlResult.refused("没有焦点输入框")
            val decision = ControlTargeting.decideSetText(toFacts(node).copy(focused = true))
            if (!decision.ok) return decision
            if (typingBlocked(node) || node.isPassword) return ControlResult.refused("拒绝写入密码框")
            if (!node.isEditable) return ControlResult.refused("焦点不在可编辑控件上")
            if (node.packageName?.toString() == service.packageName) {
                return ControlResult.refused("不会往自记自己的输入框里写")
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val wrote = try {
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (_: Exception) {
                false
            }
            return if (wrote) ControlResult.ok("已写入焦点输入框") else ControlResult.refused("系统拒绝写入")
        } finally {
            if (focused != null && roots.nodes.none { it === focused }) recycle(focused)
            roots.close()
        }
    }

    private fun collectMatches(
        roots: List<AccessibilityNodeInfo>,
        query: String,
        byId: Boolean,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        for (root in roots) {
            val batch = try {
                if (byId) root.findAccessibilityNodeInfosByViewId(query)
                else root.findAccessibilityNodeInfosByText(query)
            } catch (_: Exception) {
                null
            } ?: continue
            for (node in batch) {
                if (out.size >= MAX_MATCHES) recycle(node) else out.add(node)
            }
            if (out.size >= MAX_MATCHES) return
        }
    }

    private fun clickNode(start: AccessibilityNodeInfo): Boolean {
        val chain = ArrayList<AccessibilityNodeInfo>(4)
        try {
            var current = start
            var guard = 0
            while (guard++ < 8) {
                val canClick = current.isClickable ||
                    current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                if (current.isVisibleToUser && current.isEnabled && canClick) {
                    val clicked = try {
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (_: Exception) {
                        false
                    }
                    if (clicked) return true
                }
                val parent = try {
                    current.parent
                } catch (_: Exception) {
                    null
                } ?: return false
                chain.add(parent)
                current = parent
            }
            return false
        } finally {
            chain.forEach { recycle(it) }
        }
    }

    private fun toFacts(node: AccessibilityNodeInfo): ControlNode {
        return ControlNode(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            viewId = node.viewIdResourceName,
            password = typingBlocked(node),
            editable = node.isEditable,
            focused = node.isFocused,
            clickable = node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK },
            enabled = node.isEnabled,
            visible = node.isVisibleToUser,
            ownApp = node.packageName?.toString() == service.packageName,
        )
    }

    /** The node or its immediate parent is a password field. Never type into either. */
    private fun typingBlocked(node: AccessibilityNodeInfo): Boolean {
        if (isPassword(node)) return true
        val parent = try {
            node.parent
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            isPassword(parent)
        } finally {
            recycle(parent)
        }
    }

    private fun isPassword(node: AccessibilityNodeInfo): Boolean {
        val inputType = if (Build.VERSION.SDK_INT >= 33) node.inputType else 0
        return PasswordSignals.isPassword(node.isPassword, inputType)
    }

    private fun snapshotRoots(): Roots {
        val windows = try {
            service.windows
        } catch (_: Exception) {
            null
        }
        val nodes = ArrayList<AccessibilityNodeInfo>(4)
        if (windows != null) {
            val ranked = windows.sortedByDescending { rank(it) }
            for (window in ranked) {
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val root = try {
                    window.root
                } catch (_: Exception) {
                    null
                } ?: continue
                nodes.add(root)
            }
        }
        if (nodes.isEmpty()) {
            val root = try {
                service.rootInActiveWindow
            } catch (_: Exception) {
                null
            }
            if (root != null) nodes.add(root)
        }
        return Roots(nodes, windows)
    }

    private fun rank(window: AccessibilityWindowInfo): Int {
        val app = window.type == AccessibilityWindowInfo.TYPE_APPLICATION
        return when {
            app && window.isFocused -> 3
            app && window.isActive -> 2
            app -> 1
            else -> 0
        }
    }

    private suspend fun dispatchStroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
                .build()
        } catch (_: Exception) {
            return false
        }
        return dispatch(gesture)
    }

    private suspend fun dispatch(gesture: GestureDescription): Boolean {
        val completed = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val accepted = try {
                    service.dispatchGesture(
                        gesture,
                        object : AccessibilityService.GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(true)
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume(false)
                            }
                        },
                        null,
                    )
                } catch (_: Exception) {
                    false
                }
                if (!accepted && continuation.isActive) continuation.resume(false)
            }
        }
        return completed == true
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = service.getSystemService(WindowManager::class.java)
        if (wm != null && Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.currentWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) return bounds.width() to bounds.height()
        }
        if (wm != null) {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return metrics.widthPixels to metrics.heightPixels
            }
        }
        val metrics = service.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun gestureRefused(): String {
        return "手势被拒绝。升级后请到系统无障碍里把自记关掉再打开"
    }

    private fun recycle(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (_: Exception) {
        }
    }

    private class Roots(
        val nodes: List<AccessibilityNodeInfo>,
        private val windows: List<AccessibilityWindowInfo>?,
    ) : AutoCloseable {
        override fun close() {
            nodes.forEach { node ->
                try {
                    node.recycle()
                } catch (_: Exception) {
                }
            }
            windows?.forEach { window ->
                try {
                    window.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private companion object {
        const val MAX_MATCHES = 40
        const val TAP_DURATION_MS = 60L
        const val GESTURE_TIMEOUT_MS = 2_500L
    }
}
