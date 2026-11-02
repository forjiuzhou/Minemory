package dev.owner.ownlog.domain

fun mergeJson(existing: String?, updates: Map<String, String?>): String {
    val fields = parseFlatObject(existing)
    for ((key, value) in updates) {
        if (value == null) fields.remove(key) else fields[key] = jsonString(value)
    }
    if (fields.isEmpty()) return "{}"
    return fields.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, raw) ->
        jsonString(key) + ":" + raw
    }
}

fun parseFlatObject(json: String?): LinkedHashMap<String, String> {
    val map = LinkedHashMap<String, String>()
    if (json.isNullOrBlank()) return map
    val source = json.trim()
    if (!source.startsWith("{")) return map
    var index = 1
    fun skip() {
        while (index < source.length && source[index].isWhitespace()) index++
    }
    fun readString(): String {
        if (index >= source.length || source[index] != '"') return ""
        index++
        val sb = StringBuilder()
        while (index < source.length) {
            val ch = source[index++]
            if (ch == '\\' && index < source.length) {
                when (val escaped = source[index++]) {
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> {
                        val hex = source.substring(index, (index + 4).coerceAtMost(source.length))
                        index += hex.length
                        sb.append(hex.toIntOrNull(16)?.toChar() ?: '?')
                    }
                    else -> sb.append(escaped)
                }
            } else if (ch == '"') {
                break
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }
    fun readValue(): String {
        if (index >= source.length) return "null"
        val start = index
        when (source[index]) {
            '"' -> {
                readString()
                return source.substring(start, index)
            }
            '{', '[' -> {
                val open = source[index]
                val close = if (open == '{') '}' else ']'
                var depth = 0
                var inString = false
                var escaped = false
                while (index < source.length) {
                    val ch = source[index++]
                    if (inString) {
                        if (escaped) escaped = false
                        else if (ch == '\\') escaped = true
                        else if (ch == '"') inString = false
                    } else if (ch == '"') {
                        inString = true
                    } else if (ch == open) {
                        depth++
                    } else if (ch == close) {
                        depth--
                        if (depth == 0) break
                    }
                }
                return source.substring(start, index)
            }
            else -> {
                while (index < source.length && source[index] != ',' && source[index] != '}' && !source[index].isWhitespace()) {
                    index++
                }
                return source.substring(start, index).ifBlank { "null" }
            }
        }
    }
    while (index < source.length) {
        skip()
        if (index >= source.length || source[index] == '}') break
        if (source[index] == ',') {
            index++
            continue
        }
        if (source[index] != '"') break
        val key = readString()
        skip()
        if (index >= source.length || source[index] != ':') break
        index++
        skip()
        map[key] = readValue()
    }
    return map
}
