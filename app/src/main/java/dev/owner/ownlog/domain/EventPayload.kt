package dev.owner.ownlog.domain

import java.time.Instant
import java.util.Locale

data class OutgoingEvent(
    val id: String,
    val occurredAtMillis: Long,
    val type: String,
    val packageName: String?,
    val appLabel: String?,
    val title: String?,
    val content: String?,
    val extrasJson: String?,
    val deviceId: String,
    val createdAtMillis: Long,
)

fun isoTimestamp(millis: Long): String = Instant.ofEpochMilli(millis).toString()

fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

fun jsonStringArray(values: List<String>): String {
    return values.joinToString(separator = ",", prefix = "[", postfix = "]") { jsonString(it) }
}

fun formatDecimal(value: Double, places: Int): String {
    return String.format(Locale.US, "%.${places}f", value)
}

class JsonObj {
    private val entries = LinkedHashMap<String, String>()

    fun str(key: String, value: String?): JsonObj {
        if (value != null) entries[key] = jsonString(value)
        return this
    }

    fun num(key: String, value: Double, places: Int): JsonObj {
        if (value.isFinite()) entries[key] = formatDecimal(value, places)
        return this
    }

    fun lng(key: String, value: Long): JsonObj {
        entries[key] = value.toString()
        return this
    }

    fun bool(key: String, value: Boolean): JsonObj {
        entries[key] = if (value) "true" else "false"
        return this
    }

    fun raw(key: String, json: String): JsonObj {
        entries[key] = json
        return this
    }

    fun build(): String {
        if (entries.isEmpty()) return "{}"
        return entries.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            jsonString(key) + ":" + value
        }
    }
}

fun extrasFromMap(map: Map<String, String>): String {
    val obj = JsonObj()
    for ((key, value) in map) {
        when (key) {
            "duration_ms", "started_at" -> {
                val number = value.toLongOrNull()
                if (number != null) obj.lng(key, number) else obj.str(key, value)
            }
            "interrupted" -> obj.bool(key, value == "true")
            else -> obj.str(key, value)
        }
    }
    return obj.build()
}

fun encodeBatch(events: List<OutgoingEvent>, ownerId: String? = null): String {
    return events.joinToString(prefix = "[", postfix = "]") { event ->
        buildString {
            append("{\"id\":").append(jsonString(event.id))
            append(",\"occurred_at\":").append(jsonString(isoTimestamp(event.occurredAtMillis)))
            append(",\"type\":").append(jsonString(event.type))
            append(",\"package_name\":").append(jsonNullable(event.packageName))
            append(",\"app_label\":").append(jsonNullable(event.appLabel))
            append(",\"title\":").append(jsonNullable(event.title))
            append(",\"content\":").append(jsonNullable(event.content))
            append(",\"extras\":").append(extrasField(event.extrasJson))
            append(",\"device_id\":").append(jsonString(event.deviceId))
            if (!ownerId.isNullOrBlank()) {
                append(",\"user_id\":").append(jsonString(ownerId))
            }
            append(",\"created_at\":").append(jsonString(isoTimestamp(event.createdAtMillis)))
            append('}')
        }
    }
}

fun normalizeSupabaseEndpoint(raw: String): String {
    var base = raw.trim().trimEnd('/')
    if (base.endsWith("/rest/v1/events")) {
        return base
    }
    if (base.endsWith("/rest/v1")) {
        return "$base/events"
    }
    return "$base/rest/v1/events"
}

private fun jsonNullable(value: String?): String = if (value == null) "null" else jsonString(value)

private fun extrasField(raw: String?): String {
    if (raw.isNullOrBlank()) return "null"
    val trimmed = raw.trim()
    return if (trimmed.startsWith("{") || trimmed.startsWith("[")) trimmed else jsonString(trimmed)
}
