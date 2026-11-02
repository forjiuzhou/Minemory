package dev.owner.ownlog.domain

import java.util.Base64

const val AUTH_SIGNED_OUT = "未登录，上传已暂停。"
const val AUTH_REFRESH_FAILED = "登录已失效，上传已暂停。请重新登录。"

data class ParsedAuth(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val userId: String,
    val email: String?,
)

fun shouldRefresh(expiresAtMillis: Long, nowMillis: Long, skewMs: Long = 60_000L): Boolean {
    return expiresAtMillis <= nowMillis + skewMs
}

fun jwtSubject(token: String): String? {
    val parts = token.split('.')
    if (parts.size < 2) return null
    val payload = decodeBase64Url(parts[1]) ?: return null
    return jsonStringField(payload, "sub")?.trim()?.lowercase()?.ifBlank { null }
}

fun parseAuthResponse(json: String, nowMillis: Long): ParsedAuth? {
    val access = jsonStringField(json, "access_token")?.trim().orEmpty()
    val refresh = jsonStringField(json, "refresh_token")?.trim().orEmpty()
    if (access.isBlank() || refresh.isBlank()) return null
    val expiresIn = jsonLongField(json, "expires_in")
    val expiresAt = jsonLongField(json, "expires_at")
    val expiry = when {
        expiresIn != null && expiresIn > 0 -> nowMillis + expiresIn * 1000
        expiresAt != null && expiresAt > 1_000_000_000_000L -> expiresAt
        expiresAt != null && expiresAt > 0 -> expiresAt * 1000
        else -> nowMillis + 3_600_000
    }
    val userId = jwtSubject(access) ?: jsonStringField(json, "id")?.trim()?.lowercase()
    if (userId.isNullOrBlank()) return null
    return ParsedAuth(
        accessToken = access,
        refreshToken = refresh,
        expiresAtMillis = expiry,
        userId = userId,
        email = jsonStringField(json, "email")?.trim()?.ifBlank { null },
    )
}

fun projectRoot(raw: String): String = normalizeSupabaseEndpoint(raw).removeSuffix("/rest/v1/events")

fun jsonStringField(json: String, key: String): String? {
    val pattern = Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
    val raw = pattern.find(json)?.groupValues?.getOrNull(1) ?: return null
    return unescapeJson(raw)
}

fun jsonLongField(json: String, key: String): Long? {
    val pattern = Regex(""""${Regex.escape(key)}"\s*:\s*(-?\d+)""")
    return pattern.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()
}

private fun unescapeJson(raw: String): String {
    val sb = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val ch = raw[index]
        if (ch == '\\' && index + 1 < raw.length) {
            when (val escaped = raw[index + 1]) {
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                't' -> sb.append('\t')
                else -> sb.append(escaped)
            }
            index += 2
        } else {
            sb.append(ch)
            index++
        }
    }
    return sb.toString()
}

private fun decodeBase64Url(value: String): String? {
    return try {
        val padded = value.replace('-', '+').replace('_', '/')
            .let { it + "=".repeat((4 - it.length % 4) % 4) }
        String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}
