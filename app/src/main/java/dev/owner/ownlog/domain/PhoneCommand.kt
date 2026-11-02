package dev.owner.ownlog.domain

import java.time.Instant

/**
 * Remote command parsing, claim rules, and the text form of an accessibility tree.
 * The accessibility service only executes a [PhonePlan] this file already accepted.
 */
data class PhoneArgBag(
    val text: String? = null,
    val viewId: String? = null,
    val direction: String? = null,
    val x1: Double? = null,
    val y1: Double? = null,
    val x2: Double? = null,
    val y2: Double? = null,
    val durationMs: Long? = null,
    val packageName: String? = null,
    val name: String? = null,
    val ms: Long? = null,
) {
    fun isBlank(): Boolean {
        return text == null && viewId == null && direction == null &&
            x1 == null && y1 == null && x2 == null && y2 == null &&
            durationMs == null && packageName == null && name == null && ms == null
    }

    companion object {
        fun parse(json: String?): PhoneArgBag {
            if (json.isNullOrBlank()) return PhoneArgBag()
            val fields = parseJson(json).asObject() ?: return PhoneArgBag()
            return from(fields)
        }

        fun from(fields: Map<String, JsonValue>): PhoneArgBag {
            return PhoneArgBag(
                text = fields["text"].asText()?.trim()?.ifBlank { null },
                viewId = fields["view_id"].asText()?.trim()?.ifBlank { null },
                direction = fields["direction"].asText()?.trim()?.ifBlank { null },
                x1 = fields["x1"].asDouble(),
                y1 = fields["y1"].asDouble(),
                x2 = fields["x2"].asDouble(),
                y2 = fields["y2"].asDouble(),
                durationMs = fields["duration_ms"].asLong(),
                packageName = fields["package"].asText()?.trim()?.ifBlank { null },
                name = fields["name"].asText()?.trim()?.ifBlank { null },
                ms = fields["ms"].asLong(),
            )
        }
    }
}

data class RemoteCommand(
    val id: String,
    val action: String,
    val args: PhoneArgBag,
    val deviceId: String?,
    val status: String,
    val expiresAtMillis: Long,
    val startedAtMillis: Long?,
)

fun parseRemoteCommands(json: String): List<RemoteCommand> {
    val rows = parseJson(json).asArray() ?: return emptyList()
    return rows.mapNotNull { item ->
        val fields = item.asObject() ?: return@mapNotNull null
        val id = fields["id"].asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val action = fields["action"].asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val status = fields["status"].asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val expires = fields["expires_at"].asText()?.let(::parseInstantMillis) ?: return@mapNotNull null
        val args = fields["args"]?.let { value ->
            when (value) {
                is JsonValue.Obj -> PhoneArgBag.from(value.fields)
                is JsonValue.Str -> PhoneArgBag.parse(value.value)
                else -> PhoneArgBag()
            }
        } ?: PhoneArgBag()
        RemoteCommand(
            id = id,
            action = action,
            args = args,
            deviceId = fields["device_id"].asText()?.takeIf { it.isNotBlank() },
            status = status,
            expiresAtMillis = expires,
            startedAtMillis = fields["started_at"].asText()?.let(::parseInstantMillis),
        )
    }
}

fun parseInstantMillis(raw: String): Long? {
    return try {
        Instant.parse(raw.trim()).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}

sealed class PhonePlan {
    abstract val action: String

    data class Act(val command: ControlCommand, override val action: String) : PhonePlan()
    data class Open(val packageName: String?, val label: String?, override val action: String = "open_app") : PhonePlan()
}

sealed class PhonePlanResult {
    data class Ready(val plan: PhonePlan) : PhonePlanResult()
    data class Invalid(val message: String) : PhonePlanResult()
}

object PhoneCommands {
    fun interpret(action: String, args: PhoneArgBag): PhonePlanResult {
        val plan = when (action) {
            "back" -> bare(action, args, ControlCommand.Back)
            "home" -> bare(action, args, ControlCommand.Home)
            "recents" -> bare(action, args, ControlCommand.Recents)
            "dump_ui" -> bare(action, args, ControlCommand.DumpUi)
            "wait" -> wait(args)
            "type" -> type(args)
            "tap" -> tap(args)
            "swipe" -> swipe(args)
            "open_app" -> open(args)
            else -> return PhonePlanResult.Invalid("认不出这条命令")
        }
        return if (plan == null) {
            PhonePlanResult.Invalid(invalidMessage(action, args))
        } else {
            PhonePlanResult.Ready(plan)
        }
    }

    private fun bare(action: String, args: PhoneArgBag, command: ControlCommand): PhonePlan? {
        if (!args.isBlank()) return null
        return PhonePlan.Act(command, action)
    }

    private fun wait(args: PhoneArgBag): PhonePlan? {
        val ms = args.ms ?: return null
        if (!args.copy(ms = null).isBlank()) return null
        val command = ControlCommand.Wait(ms)
        if (ControlPolicy.reject(command) != null) return null
        return PhonePlan.Act(command, "wait")
    }

    private fun type(args: PhoneArgBag): PhonePlan? {
        val text = args.text ?: return null
        if (!args.copy(text = null).isBlank()) return null
        val command = ControlCommand.SetText(text)
        if (ControlPolicy.reject(command) != null) return null
        return PhonePlan.Act(command, "type")
    }

    private fun tap(args: PhoneArgBag): PhonePlan? {
        if (!args.copy(text = null, viewId = null).isBlank()) return null
        val text = args.text
        val id = args.viewId
        val command = when {
            text != null && id != null -> return null
            id != null -> ControlCommand.TapId(id)
            text != null -> ControlCommand.TapText(text)
            else -> return null
        }
        if (ControlPolicy.reject(command) != null) return null
        return PhonePlan.Act(command, "tap")
    }

    private fun swipe(args: PhoneArgBag): PhonePlan? {
        val direction = args.direction?.lowercase()
        val hasPath = args.x1 != null || args.y1 != null || args.x2 != null || args.y2 != null
        if (direction != null && hasPath) return null
        if (direction != null) {
            if (!args.copy(direction = null).isBlank()) return null
            val swipe = when (direction) {
                "up" -> SwipeDirection.UP
                "down" -> SwipeDirection.DOWN
                "left" -> SwipeDirection.LEFT
                "right" -> SwipeDirection.RIGHT
                else -> return null
            }
            return PhonePlan.Act(ControlCommand.Swipe(swipe), "swipe")
        }
        if (!args.copy(x1 = null, y1 = null, x2 = null, y2 = null, durationMs = null).isBlank()) return null
        if (args.x1 == null || args.y1 == null || args.x2 == null || args.y2 == null) return null
        val command = ControlCommand.SwipePath(
            x1 = args.x1.toFloat(),
            y1 = args.y1.toFloat(),
            x2 = args.x2.toFloat(),
            y2 = args.y2.toFloat(),
            durationMs = args.durationMs ?: ControlGeometry.SWIPE_DURATION_MS,
        )
        if (ControlPolicy.reject(command) != null) return null
        return PhonePlan.Act(command, "swipe")
    }

    private fun open(args: PhoneArgBag): PhonePlan? {
        if (!args.copy(packageName = null, name = null).isBlank()) return null
        val pkg = args.packageName
        val name = args.name
        if (pkg == null && name == null) return null
        if (pkg != null && PhoneApps.rejectPackage(pkg, ownPackage = "") != null) return null
        if (name != null && name.length > 80) return null
        return PhonePlan.Open(pkg, name)
    }

    private fun invalidMessage(action: String, args: PhoneArgBag): String {
        return when (action) {
            "tap" -> when {
                args.text != null && args.viewId != null -> "tap 只能给文字或 view id 其中一个"
                args.viewId != null -> "view id 要写成 包名:id/名字"
                args.text.isNullOrBlank() -> "tap 需要 text 或 view_id"
                else -> "找不到要点的文字"
            }
            "swipe" -> "swipe 需要 direction，或 x1 y1 x2 y2"
            "type" -> ControlPolicy.reject(ControlCommand.SetText(args.text.orEmpty()))?.message ?: "type 需要 text"
            "wait" -> "等待时间要在 1 到 10000 毫秒"
            "open_app" -> when {
                args.packageName != null -> PhoneApps.rejectPackage(args.packageName, "") ?: "open_app 需要 package 或 name"
                else -> "open_app 需要 package 或 name"
            }
            "back", "home", "recents", "dump_ui" -> "这条命令不带参数"
            else -> "认不出这条命令"
        }
    }
}

object PhoneClaim {
    const val RUNNING_STALE_MS = 60_000L

    fun acceptable(
        status: String,
        expiresAtMillis: Long,
        nowMillis: Long,
        commandDeviceId: String?,
        localDeviceId: String,
    ): Boolean {
        if (status != "pending") return false
        if (expiresAtMillis <= nowMillis) return false
        if (!commandDeviceId.isNullOrBlank() && commandDeviceId != localDeviceId) return false
        return true
    }

    fun runningIsStale(startedAtMillis: Long?, nowMillis: Long, limitMs: Long = RUNNING_STALE_MS): Boolean {
        if (startedAtMillis == null) return true
        return nowMillis - startedAtMillis >= limitMs
    }
}

data class UiTreeNode(
    val packageName: String?,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val clickable: Boolean,
    val password: Boolean,
    val ownApp: Boolean,
    val bounds: String,
    val depth: Int,
)

data class UiTreeDump(
    val foregroundPackage: String?,
    val text: String,
    val truncated: Boolean,
    val nodeCount: Int,
)

object UiTreeText {
    const val MAX_CHARS = 80_000
    const val MAX_NODES = 400
    private const val FIELD_CHARS = 180

    fun format(foregroundPackage: String?, nodes: List<UiTreeNode>): UiTreeDump {
        val out = StringBuilder()
        out.append("package=").append(foregroundPackage.orEmpty()).append('\n')
        var shown = 0
        var truncated = nodes.size > MAX_NODES
        for (node in nodes) {
            if (shown >= MAX_NODES) {
                truncated = true
                break
            }
            val line = render(node)
            if (out.length + line.length + 1 > MAX_CHARS) {
                truncated = true
                break
            }
            out.append(line).append('\n')
            shown++
        }
        return UiTreeDump(
            foregroundPackage = foregroundPackage,
            text = out.toString().trimEnd(),
            truncated = truncated,
            nodeCount = shown,
        )
    }

    fun render(node: UiTreeNode): String {
        val text = if (node.password) "" else clip(node.text)
        val desc = if (node.password) "" else clip(node.contentDescription)
        return buildString {
            append("d=").append(node.depth.coerceAtLeast(0))
            append(" pkg=").append(node.packageName.orEmpty())
            append(" text=").append(jsonString(text))
            append(" desc=").append(jsonString(desc))
            append(" id=").append(node.viewId.orEmpty())
            append(" clickable=").append(if (node.clickable) "1" else "0")
            if (node.password) append(" password=1")
            if (node.ownApp) append(" own=1")
            append(" bounds=").append(node.bounds)
        }
    }

    private fun clip(value: String?): String {
        val clean = value.orEmpty().replace('\n', ' ').replace('\r', ' ')
        return if (clean.length <= FIELD_CHARS) clean else clean.take(FIELD_CHARS)
    }
}

object PhoneResults {
    fun json(
        ok: Boolean,
        message: String,
        error: String?,
        foregroundPackage: String?,
        uiTree: String?,
        uiTruncated: Boolean,
        durationMs: Long,
    ): String {
        return JsonObj()
            .bool("ok", ok)
            .str("message", message)
            .raw("error", if (error == null) "null" else jsonString(error))
            .raw("foreground_package", if (foregroundPackage == null) "null" else jsonString(foregroundPackage))
            .str("ui_tree", uiTree)
            .bool("ui_truncated", uiTruncated)
            .lng("duration_ms", durationMs.coerceAtLeast(0))
            .build()
    }
}

data class InstalledApp(
    val packageName: String,
    val label: String,
)

sealed class AppChoice {
    data class One(val packageName: String) : AppChoice()
    data class Rejected(val message: String) : AppChoice()
}

object PhoneApps {
    private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    private val aliases = listOf(
        Alias(listOf("微信", "wechat", "weixin"), "com.tencent.mm"),
        Alias(listOf("小红书", "xiaohongshu", "redbook"), "com.xingin.xhs"),
        Alias(listOf("拼多多", "pinduoduo"), "com.xunmeng.pinduoduo"),
    )

    fun aliasPackage(query: String): String? {
        val key = query.trim().lowercase()
        return aliases.firstOrNull { alias -> alias.names.any { it.lowercase() == key } }?.packageName
    }

    fun rejectPackage(packageName: String, ownPackage: String): String? {
        if (!packagePattern.matches(packageName)) return "包名不像 Android 包名"
        if (ownPackage.isNotEmpty() && packageName == ownPackage) return "不会打开自记自己"
        return null
    }

    fun choose(query: String, installed: List<InstalledApp>, ownPackage: String): AppChoice {
        val name = query.trim()
        if (name.isEmpty()) return AppChoice.Rejected("没有应用名")
        if (name.length > 80) return AppChoice.Rejected("应用名太长")
        val alias = aliasPackage(name)
        if (alias != null) {
            if (alias == ownPackage) return AppChoice.Rejected("不会打开自记自己")
            return AppChoice.One(alias)
        }
        val others = installed.filter { it.packageName != ownPackage && it.packageName.isNotBlank() }
        val exact = others.filter { it.label.equals(name, ignoreCase = true) }
        if (exact.size == 1) return AppChoice.One(exact[0].packageName)
        if (exact.size > 1) return AppChoice.Rejected("找到多个叫「${name.take(40)}」的应用")
        val fuzzy = others.filter { app ->
            app.label.contains(name, ignoreCase = true) || app.packageName.contains(name, ignoreCase = true)
        }
        if (fuzzy.size == 1) return AppChoice.One(fuzzy[0].packageName)
        if (fuzzy.size > 1) return AppChoice.Rejected("找到多个匹配「${name.take(40)}」的应用")
        return AppChoice.Rejected("没找到应用「${name.take(40)}」")
    }

    private data class Alias(val names: List<String>, val packageName: String)
}
