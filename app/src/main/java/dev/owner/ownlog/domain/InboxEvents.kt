package dev.owner.ownlog.domain

data class MappedEvent(
    val type: String,
    val title: String?,
    val content: String?,
    val extrasJson: String,
)

fun mapNote(text: String, note: String?): MappedEvent {
    val body = text.trim()
    val extras = JsonObj().str("note", note.clean()).buildOrNull() ?: "{}"
    return MappedEvent(
        type = "note",
        title = firstLine(body),
        content = body,
        extrasJson = extras,
    )
}

fun mapPhoto(
    ocrText: String?,
    note: String?,
    filePath: String,
    mime: String,
    bytes: Long,
): MappedEvent {
    val text = ocrText?.trim()?.ifBlank { null }
    val extras = fileExtras(note, filePath, mime, bytes).bool("image", true).build()
    return MappedEvent(
        type = "photo",
        title = firstLine(text) ?: note.clean()?.take(80) ?: "照片",
        content = text,
        extrasJson = extras,
    )
}

fun mapVoice(
    transcript: String?,
    note: String?,
    filePath: String,
    mime: String,
    bytes: Long,
): MappedEvent {
    val text = transcript?.trim()?.ifBlank { null }
    val extras = fileExtras(note, filePath, mime, bytes).bool("transcribed", text != null).build()
    return MappedEvent(
        type = "voice",
        title = firstLine(text) ?: note.clean()?.take(80) ?: "语音",
        content = text,
        extrasJson = extras,
    )
}

fun mapShare(
    subject: String?,
    text: String?,
    note: String?,
    explicitPackage: String?,
    image: Boolean,
    filePath: String? = null,
    mime: String? = null,
    bytes: Long? = null,
): MappedEvent {
    val body = shareBody(subject, text)
    val url = extractUrl(body)
    val source = inferSourcePackage(explicitPackage, url)
    val extras = JsonObj()
        .str("url", url)
        .str("note", note.clean())
        .str("source_package", source)
        .str("source_host", hostOf(url))
        .str("shared_text", if (image) body else null)
        .str("file_path", filePath)
        .str("mime", mime)
        .str("bucket", if (filePath != null) "inbox" else null)
    if (bytes != null && bytes >= 0) extras.lng("bytes", bytes)
    if (image) extras.bool("image", true)
    return MappedEvent(
        type = "share",
        title = shareTitle(subject, body),
        content = body,
        extrasJson = extras.build(),
    )
}

private fun fileExtras(note: String?, filePath: String, mime: String, bytes: Long): JsonObj {
    return JsonObj()
        .str("note", note.clean())
        .str("file_path", filePath)
        .str("mime", mime)
        .str("bucket", "inbox")
        .lng("bytes", bytes)
}

private fun firstLine(text: String?): String? {
    return text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(80)
}

private fun String?.clean(): String? = this?.trim()?.ifBlank { null }

private fun JsonObj.buildOrNull(): String? = build().takeIf { it != "{}" }
