package dev.owner.ownlog.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.domain.ClickSnap
import dev.owner.ownlog.domain.ControlCommand
import dev.owner.ownlog.domain.ControlResult
import dev.owner.ownlog.domain.PasswordSignals
import dev.owner.ownlog.domain.TextNode
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class OwnAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var snapshotScheduled = false
    private var pendingTitle: String? = null
    private var pendingWindowKey: String? = null
    private var pendingStateChange = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val pipeline = pipelineOrNull() ?: return
            val now = System.currentTimeMillis()
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pipeline.onScreenOff(now)
                Intent.ACTION_SCREEN_ON -> pipeline.onScreenOn(now)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        CaptureBridge.attachAccessibility(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }
    }

    override fun onDestroy() {
        CaptureBridge.detachAccessibility(this)
        mainHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val pipeline = pipelineOrNull() ?: return
        val now = System.currentTimeMillis()
        val overlay = isOverlay(event)
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (!overlay) {
                    pipeline.onClick(pkg, readClick(event), now)
                    pipeline.onInteraction(pkg, scrolled = false, now)
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (!overlay) pipeline.onInteraction(pkg, scrolled = true, now)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val title = windowTitle(event)
                if (!overlay) pipeline.onForeground(pkg, title, now)
                if (!overlay) scheduleSnapshot(event.windowId, title, stateChange = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!overlay) scheduleSnapshot(event.windowId, title = null, stateChange = false)
            }
        }
    }

    internal suspend fun runControl(command: ControlCommand): ControlResult {
        val app = application as? OwnLogApp ?: return ControlResult.refused("控机不可用")
        if (!app.settings.get().enableControl) return ControlResult.refused("控机已关闭")
        return AccessibilityActuator(this).run(command)
    }

    suspend fun captureViaAccessibility(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap = hardwareToBitmap(screenshot.hardwareBuffer, screenshot.colorSpace)
                            if (continuation.isActive) continuation.resume(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun scheduleSnapshot(windowId: Int, title: String?, stateChange: Boolean) {
        pendingWindowKey = windowId.toString()
        if (title != null) pendingTitle = title
        if (stateChange) pendingStateChange = true
        if (snapshotScheduled) return
        snapshotScheduled = true
        mainHandler.postDelayed({
            snapshotScheduled = false
            emitSnapshot()
        }, 600)
    }

    private fun emitSnapshot() {
        val pipeline = pipelineOrNull() ?: return
        val stateChange = pendingStateChange
        val title = pendingTitle
        val windowKey = pendingWindowKey ?: "0"
        pendingTitle = null
        pendingStateChange = false
        val captured = captureNodes()
        val pkg = captured.packageName ?: return
        if (pkg == packageName) return
        pipeline.onScreen(
            packageName = pkg,
            windowKey = windowKey,
            windowTitle = title,
            nodes = captured.nodes,
            stateChange = stateChange,
            now = System.currentTimeMillis(),
        )
    }

    private fun captureNodes(): NodeCapture {
        val list = windows
        if (list == null) {
            val root = rootInActiveWindow ?: return NodeCapture(null, emptyList())
            return try {
                NodeCapture(root.packageName?.toString(), snapshotTree(root))
            } finally {
                root.recycle()
            }
        }
        var root: AccessibilityNodeInfo? = null
        try {
            val apps = list.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val chosen = apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive } ?: apps.firstOrNull()
            root = chosen?.root
            val nodes = if (root != null) snapshotTree(root) else emptyList()
            return NodeCapture(root?.packageName?.toString(), nodes)
        } catch (_: Exception) {
            return NodeCapture(null, emptyList())
        } finally {
            try {
                root?.recycle()
            } catch (_: Exception) {
            }
            list.forEach { window ->
                try {
                    window.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun snapshotTree(root: AccessibilityNodeInfo): List<TextNode> {
        val out = ArrayList<TextNode>(64)
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (out.size >= 250 || depth > 35) return
            val inputType = if (Build.VERSION.SDK_INT >= 33) node.inputType else 0
            if (node.isVisibleToUser) {
                val text = node.text?.toString()
                val desc = node.contentDescription?.toString()
                if (!text.isNullOrBlank() || !desc.isNullOrBlank() || node.isPassword) {
                    out.add(
                        TextNode(
                            text = text,
                            contentDescription = desc,
                            viewId = node.viewIdResourceName,
                            isPassword = node.isPassword,
                            inputType = inputType,
                        ),
                    )
                }
            }
            for (index in 0 until node.childCount) {
                val child = try {
                    node.getChild(index)
                } catch (_: Exception) {
                    null
                } ?: continue
                try {
                    walk(child, depth + 1)
                } finally {
                    try {
                        child.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        walk(root, 0)
        return out
    }

    private fun readClick(event: AccessibilityEvent): ClickSnap {
        val source = event.source ?: return ClickSnap(null, null, null, null, false, 0)
        return try {
            describeClick(source)
        } finally {
            source.recycle()
        }
    }

    private fun describeClick(source: AccessibilityNodeInfo): ClickSnap {
        val inputType = if (Build.VERSION.SDK_INT >= 33) source.inputType else 0
        if (PasswordSignals.isPassword(source.isPassword, inputType)) {
            return ClickSnap(null, null, source.viewIdResourceName, source.className?.toString(), true, inputType)
        }
        var text = source.text?.toString()
        var desc = source.contentDescription?.toString()
        if (text.isNullOrBlank() && desc.isNullOrBlank()) {
            text = firstChildText(source)
        }
        if (text.isNullOrBlank() && desc.isNullOrBlank()) {
            val parent = source.parent
            if (parent != null) {
                try {
                    val parentType = if (Build.VERSION.SDK_INT >= 33) parent.inputType else 0
                    if (!PasswordSignals.isPassword(parent.isPassword, parentType)) {
                        text = parent.text?.toString()
                        desc = parent.contentDescription?.toString()
                    }
                } finally {
                    parent.recycle()
                }
            }
        }
        return ClickSnap(text, desc, source.viewIdResourceName, source.className?.toString(), false, inputType)
    }

    private fun firstChildText(node: AccessibilityNodeInfo): String? {
        for (index in 0 until node.childCount.coerceAtMost(6)) {
            val child = try {
                node.getChild(index)
            } catch (_: Exception) {
                null
            } ?: continue
            try {
                val inputType = if (Build.VERSION.SDK_INT >= 33) child.inputType else 0
                if (PasswordSignals.isPassword(child.isPassword, inputType)) continue
                val text = child.text?.toString()?.takeIf { it.isNotBlank() }
                if (text != null) return text
                val desc = child.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                if (desc != null) return desc
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun windowTitle(event: AccessibilityEvent): String? {
        val source = event.source
        val password = if (source == null) {
            false
        } else {
            try {
                val inputType = if (Build.VERSION.SDK_INT >= 33) source.inputType else 0
                PasswordSignals.isPassword(source.isPassword, inputType)
            } finally {
                source.recycle()
            }
        }
        if (password) return null
        return event.text?.firstOrNull()?.toString()
    }

    private fun isOverlay(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString().orEmpty()
        if (className.contains("inputmethodservice", ignoreCase = true)) return true
        val list = windows ?: return false
        try {
            for (window in list) {
                if (window.id != event.windowId) continue
                return when (window.type) {
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                    AccessibilityWindowInfo.TYPE_SYSTEM,
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                    AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER,
                    AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY,
                    -> true
                    else -> false
                }
            }
            return false
        } finally {
            list.forEach { window ->
                try {
                    window.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun pipelineOrNull() = (application as? OwnLogApp)?.pipeline

    private data class NodeCapture(val packageName: String?, val nodes: List<TextNode>)

    companion object {
        private fun hardwareToBitmap(buffer: HardwareBuffer, colorSpace: ColorSpace): Bitmap? {
            return try {
                val hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                buffer.close()
                val copy = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                hardware?.recycle()
                copy
            } catch (_: Exception) {
                try {
                    buffer.close()
                } catch (_: Exception) {
                }
                null
            }
        }
    }
}
