package dev.owner.ownlog.domain

/**
 * One-line phone control. Parsing and safety decisions stay free of Android
 * types so they can be unit tested. The accessibility service only acts after
 * [ControlPolicy.reject] and [ControlTargeting.decideSetText] allow it.
 */
sealed class ControlCommand {
    data class TapText(val text: String) : ControlCommand()
    data class TapId(val viewId: String) : ControlCommand()
    data object Back : ControlCommand()
    data object Home : ControlCommand()
    data object Recents : ControlCommand()
    data class Swipe(val direction: SwipeDirection) : ControlCommand()
    data class SwipePath(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long,
    ) : ControlCommand()
    data class SetText(val text: String) : ControlCommand()
    data class Wait(val millis: Long) : ControlCommand()
    data object DumpUi : ControlCommand()

    /** Tap, type, and swipe should hit the app underneath 自记, not this screen. */
    val backgroundsFirst: Boolean
        get() = this is TapText || this is TapId || this is SetText || this is Swipe || this is SwipePath
}

enum class SwipeDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
}

data class ControlResult(val ok: Boolean, val message: String) {
    fun wire(): String = if (ok) "ok\t$message" else "refused\t$message"

    companion object {
        fun ok(message: String) = ControlResult(true, message)
        fun refused(message: String) = ControlResult(false, message)

        fun parseWire(raw: String): ControlResult {
            val parts = raw.split('\t', limit = 2)
            return ControlResult(parts.firstOrNull() == "ok", parts.getOrElse(1) { "" })
        }
    }
}

data class ControlNode(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val password: Boolean,
    val editable: Boolean,
    val focused: Boolean,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val ownApp: Boolean,
)

data class SwipeStroke(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val durationMs: Long,
)

object ControlPolicy {
    const val ENABLED_BY_DEFAULT = true
    const val REMOTE_ENABLED_BY_DEFAULT = true
    const val MAX_SET_TEXT = 2000
    const val MAX_TAP_TEXT = 200
    const val MAX_WAIT_MS = 10_000L

    fun controlEnabled(stored: Boolean?): Boolean = stored ?: ENABLED_BY_DEFAULT

    /** Remote control rides on the local switch. Unset means on, but only while local control is on. */
    fun remoteEnabled(stored: Boolean?, controlEnabled: Boolean): Boolean {
        if (!controlEnabled) return false
        return stored ?: REMOTE_ENABLED_BY_DEFAULT
    }

    fun isViewId(raw: String): Boolean {
        val id = raw.trim()
        val marker = id.indexOf(":id/")
        if (marker <= 0 || id.length > MAX_TAP_TEXT) return false
        return id.none { it.isWhitespace() }
    }

    /** Null means the command may proceed to the accessibility service. */
    fun reject(command: ControlCommand): ControlResult? = when (command) {
        is ControlCommand.SetText -> when {
            command.text.isEmpty() -> ControlResult.refused("没有要写入的文字")
            command.text.length > MAX_SET_TEXT -> ControlResult.refused("文字太长")
            else -> null
        }
        is ControlCommand.TapText -> when {
            command.text.isBlank() || command.text.length > MAX_TAP_TEXT ->
                ControlResult.refused("找不到要点的文字")
            else -> null
        }
        is ControlCommand.TapId -> when {
            !isViewId(command.viewId) -> ControlResult.refused("view id 要写成 包名:id/名字")
            else -> null
        }
        is ControlCommand.Wait -> when {
            command.millis !in 1..MAX_WAIT_MS -> ControlResult.refused("等待时间要在 1 到 10000 毫秒")
            else -> null
        }
        is ControlCommand.SwipePath -> when {
            !command.sane() -> ControlResult.refused("滑动坐标无效")
            else -> null
        }
        else -> null
    }
}

private fun ControlCommand.SwipePath.sane(): Boolean {
    val coords = listOf(x1, y1, x2, y2)
    if (coords.any { !it.isFinite() || it < 0f || it > 20_000f }) return false
    if (durationMs !in 50L..3_000L) return false
    return x1 != x2 || y1 != y2
}

object ControlCommands {
    val examples: String = """
        tap 搜索
        tap id com.android.settings:id/search
        back
        home
        recents
        swipe up
        type 你好
    """.trimIndent()

    fun parse(raw: String): ControlCommand? {
        val line = raw.trim().replace(Regex("\\s+"), " ")
        if (line.isEmpty() || line.length > ControlPolicy.MAX_SET_TEXT + 20) return null
        val head = line.substringBefore(' ')
        val rest = if (line.contains(' ')) line.substringAfter(' ').trim() else ""
        return when (head.lowercase()) {
            "back", "返回" -> emptyOnly(rest) { ControlCommand.Back }
            "home", "主页", "桌面" -> emptyOnly(rest) { ControlCommand.Home }
            "recents", "recent", "最近", "最近任务" -> emptyOnly(rest) { ControlCommand.Recents }
            "上滑", "向上滑" -> emptyOnly(rest) { ControlCommand.Swipe(SwipeDirection.UP) }
            "下滑", "向下滑" -> emptyOnly(rest) { ControlCommand.Swipe(SwipeDirection.DOWN) }
            "左滑", "向左滑" -> emptyOnly(rest) { ControlCommand.Swipe(SwipeDirection.LEFT) }
            "右滑", "向右滑" -> emptyOnly(rest) { ControlCommand.Swipe(SwipeDirection.RIGHT) }
            "swipe", "滑" -> parseSwipe(rest)
            "tap", "点击" -> parseTap(rest)
            "tapid", "点击id" -> parseTapId(rest)
            "type", "输入", "写入", "input" -> if (rest.isEmpty()) null else ControlCommand.SetText(rest)
            else -> null
        }
    }

    private fun emptyOnly(rest: String, command: () -> ControlCommand): ControlCommand? {
        return if (rest.isEmpty()) command() else null
    }

    private fun parseSwipe(rest: String): ControlCommand? {
        if (rest.isEmpty() || rest.contains(' ')) return null
        val direction = when (rest.lowercase()) {
            "up", "上" -> SwipeDirection.UP
            "down", "下" -> SwipeDirection.DOWN
            "left", "左" -> SwipeDirection.LEFT
            "right", "右" -> SwipeDirection.RIGHT
            else -> return null
        }
        return ControlCommand.Swipe(direction)
    }

    private fun parseTap(rest: String): ControlCommand? {
        if (rest.isEmpty()) return null
        val lower = rest.lowercase()
        if (lower == "id" || lower == "text") return null
        if (lower.startsWith("id ")) return parseTapId(rest.substring(3).trim())
        if (lower.startsWith("text ")) {
            val text = rest.substring(5).trim()
            return if (text.isEmpty()) null else ControlCommand.TapText(text)
        }
        if (ControlPolicy.isViewId(rest)) return ControlCommand.TapId(rest)
        return ControlCommand.TapText(rest)
    }

    private fun parseTapId(rest: String): ControlCommand? {
        if (rest.isEmpty() || rest.any { it.isWhitespace() }) return null
        return ControlCommand.TapId(rest)
    }
}

object ControlTargeting {
    fun bestTapIndex(nodes: List<ControlNode>, query: String, byId: Boolean): Int {
        var best = -1
        var bestScore = 0
        nodes.forEachIndexed { index, node ->
            if (node.ownApp || !node.visible || !node.enabled) return@forEachIndexed
            val score = tapScore(node, query, byId)
            if (score > bestScore) {
                bestScore = score
                best = index
            }
        }
        return best
    }

    fun missReason(nodes: List<ControlNode>, query: String, byId: Boolean): String {
        val own = nodes.any { it.ownApp && tapScore(it, query, byId) > 0 }
        val shown = query.take(40)
        return if (own) "只找到自记自己的控件" else "没找到「$shown」"
    }

    /**
     * Set text only on the focused editable node.
     * [ControlNode.password] must already include the node itself and its parent.
     * A password node is refused even when it is also editable.
     */
    fun decideSetText(node: ControlNode?): ControlResult {
        if (node == null || !node.focused) return ControlResult.refused("没有焦点输入框")
        if (node.password) return ControlResult.refused("拒绝写入密码框")
        if (!node.editable) return ControlResult.refused("焦点不在可编辑控件上")
        if (node.ownApp) return ControlResult.refused("不会往自记自己的输入框里写")
        if (!node.enabled || !node.visible) return ControlResult.refused("输入框不可用")
        return ControlResult.ok("允许写入")
    }

    private fun tapScore(node: ControlNode, query: String, byId: Boolean): Int {
        if (byId) return if (node.viewId == query) 4 else 0
        val wanted = query.trim()
        if (wanted.isEmpty()) return 0
        val text = node.text.orEmpty()
        val desc = node.contentDescription.orEmpty()
        val exact = text.equals(wanted, ignoreCase = true) || desc.equals(wanted, ignoreCase = true)
        val contains = text.contains(wanted, ignoreCase = true) || desc.contains(wanted, ignoreCase = true)
        var score = when {
            exact -> 4
            contains -> 2
            else -> 0
        }
        if (score > 0 && node.clickable) score += 1
        return score
    }
}

object ControlGeometry {
    const val SWIPE_DURATION_MS = 320L

    fun swipeStroke(direction: SwipeDirection, width: Int, height: Int): SwipeStroke {
        val cx = width / 2f
        val cy = height / 2f
        val dx = width * 0.35f
        val dy = height * 0.30f
        val (x1, y1, x2, y2) = when (direction) {
            SwipeDirection.UP -> listOf(cx, cy + dy, cx, cy - dy)
            SwipeDirection.DOWN -> listOf(cx, cy - dy, cx, cy + dy)
            SwipeDirection.LEFT -> listOf(cx + dx, cy, cx - dx, cy)
            SwipeDirection.RIGHT -> listOf(cx - dx, cy, cx + dx, cy)
        }
        return SwipeStroke(
            x1 = axis(x1, width),
            y1 = axis(y1, height),
            x2 = axis(x2, width),
            y2 = axis(y2, height),
            durationMs = SWIPE_DURATION_MS,
        )
    }

    private fun axis(value: Float, size: Int): Float {
        val max = (size - 8).coerceAtLeast(8).toFloat()
        return value.coerceIn(8f, max)
    }
}
