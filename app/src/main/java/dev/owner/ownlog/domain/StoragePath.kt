package dev.owner.ownlog.domain

import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset

fun inboxObjectPath(ownerId: String, atMillis: Long, id: String, extension: String): String {
    val stamp = Instant.ofEpochMilli(atMillis).atZone(ZoneOffset.UTC)
    val year = stamp.year.toString()
    val month = "%02d".format(stamp.monthValue)
    val owner = ownerId.trim().ifBlank { "pending" }.replace('/', '_').lowercase()
    val ext = extension.trim().trim('.').lowercase().filter { it.isLetterOrDigit() }.ifBlank { "bin" }
    return "$owner/$year/$month/${id.trim()}.$ext"
}

fun objectKey(ownerId: String, storedPath: String): String {
    val owner = ownerId.trim().lowercase()
    val parts = storedPath.split('/').filter { it.isNotEmpty() }
    if (owner.isBlank()) return parts.joinToString("/")
    if (parts.firstOrNull() == owner && parts.size >= 4) return parts.joinToString("/")
    val relative = when {
        parts.size >= 4 -> parts.takeLast(3)
        parts.firstOrNull() == "pending" -> parts.drop(1)
        else -> parts
    }
    return (listOf(owner) + relative).joinToString("/")
}

fun extensionFor(mime: String?, displayName: String?): String {
    val fromMime = when (mime?.substringBefore(';')?.trim()?.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic", "image/heif" -> "heic"
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a"
        "audio/mpeg" -> "mp3"
        "video/mp4" -> "mp4"
        "application/pdf" -> "pdf"
        "text/plain" -> "txt"
        else -> null
    }
    if (fromMime != null) return fromMime
    val fromName = displayName
        ?.substringAfterLast('.', "")
        ?.lowercase()
        ?.takeIf { it.length in 1..8 && it.all { ch -> ch.isLetterOrDigit() } }
    return fromName ?: "bin"
}

fun storageObjectUrl(projectUrl: String, objectPath: String): String {
    var base = projectUrl.trim().trimEnd('/')
    for (suffix in listOf("/rest/v1/events", "/rest/v1")) {
        if (base.endsWith(suffix)) base = base.removeSuffix(suffix).trimEnd('/')
    }
    val encoded = objectPath.split('/').filter { it.isNotEmpty() }.joinToString("/") { segment ->
        URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
    }
    return "$base/storage/v1/object/inbox/$encoded"
}

fun encodeEventPatch(title: String? = null, content: String? = null, extrasJson: String? = null): String {
    val parts = mutableListOf<String>()
    if (title != null) parts += "\"title\":${jsonString(title)}"
    if (content != null) parts += "\"content\":${jsonString(content)}"
    if (!extrasJson.isNullOrBlank()) parts += "\"extras\":${extrasJson.trim()}"
    return "{${parts.joinToString(",")}}"
}
