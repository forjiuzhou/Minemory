package dev.owner.ownlog.domain

sealed class JsonValue {
    data class Str(val value: String) : JsonValue()
    data class Num(val value: String) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
}

fun parseJson(raw: String): JsonValue? = JsonParser(raw).parse()

fun JsonValue?.asObject(): Map<String, JsonValue>? = (this as? JsonValue.Obj)?.fields

fun JsonValue?.asArray(): List<JsonValue>? = (this as? JsonValue.Arr)?.items

fun JsonValue?.asText(): String? = (this as? JsonValue.Str)?.value

fun JsonValue?.asLong(): Long? = when (this) {
    is JsonValue.Num -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
    is JsonValue.Str -> value.toLongOrNull()
    else -> null
}

fun JsonValue?.asDouble(): Double? = when (this) {
    is JsonValue.Num -> value.toDoubleOrNull()
    is JsonValue.Str -> value.toDoubleOrNull()
    else -> null
}

private class JsonParser(private val raw: String) {
    private var index = 0

    fun parse(): JsonValue? {
        val value = value() ?: return null
        skip()
        return if (index == raw.length) value else null
    }

    private fun value(): JsonValue? {
        skip()
        if (index >= raw.length) return null
        return when (raw[index]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> JsonValue.Str(string() ?: return null)
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> number()
        }
    }

    private fun objectValue(): JsonValue? {
        index++
        val fields = LinkedHashMap<String, JsonValue>()
        skip()
        if (peek('}')) {
            index++
            return JsonValue.Obj(fields)
        }
        while (index < raw.length) {
            skip()
            if (index >= raw.length || raw[index] != '"') return null
            val key = string() ?: return null
            skip()
            if (!peek(':')) return null
            index++
            val child = value() ?: return null
            fields[key] = child
            skip()
            when {
                peek(',') -> index++
                peek('}') -> {
                    index++
                    return JsonValue.Obj(fields)
                }
                else -> return null
            }
        }
        return null
    }

    private fun arrayValue(): JsonValue? {
        index++
        val items = ArrayList<JsonValue>()
        skip()
        if (peek(']')) {
            index++
            return JsonValue.Arr(items)
        }
        while (index < raw.length) {
            items.add(value() ?: return null)
            skip()
            when {
                peek(',') -> index++
                peek(']') -> {
                    index++
                    return JsonValue.Arr(items)
                }
                else -> return null
            }
        }
        return null
    }

    private fun string(): String? {
        if (!peek('"')) return null
        index++
        val out = StringBuilder()
        while (index < raw.length) {
            val ch = raw[index]
            when (ch) {
                '"' -> {
                    index++
                    return out.toString()
                }
                '\\' -> {
                    if (index + 1 >= raw.length) return null
                    when (val escaped = raw[index + 1]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (index + 5 >= raw.length) return null
                            val hex = raw.substring(index + 2, index + 6)
                            val code = hex.toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            index += 4
                        }
                        else -> return null
                    }
                    index += 2
                }
                else -> {
                    if (ch.code < 0x20) return null
                    out.append(ch)
                    index++
                }
            }
        }
        return null
    }

    private fun number(): JsonValue? {
        val start = index
        if (peek('-')) index++
        if (index >= raw.length || !raw[index].isDigit()) return null
        while (index < raw.length && raw[index].isDigit()) index++
        if (peek('.')) {
            index++
            if (index >= raw.length || !raw[index].isDigit()) return null
            while (index < raw.length && raw[index].isDigit()) index++
        }
        if (index < raw.length && (raw[index] == 'e' || raw[index] == 'E')) {
            index++
            if (peek('+') || peek('-')) index++
            if (index >= raw.length || !raw[index].isDigit()) return null
            while (index < raw.length && raw[index].isDigit()) index++
        }
        return JsonValue.Num(raw.substring(start, index))
    }

    private fun literal(word: String, value: JsonValue): JsonValue? {
        if (!raw.startsWith(word, index)) return null
        index += word.length
        return value
    }

    private fun skip() {
        while (index < raw.length && raw[index].isWhitespace()) index++
    }

    private fun peek(ch: Char): Boolean = index < raw.length && raw[index] == ch
}
