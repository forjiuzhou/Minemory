package dev.owner.ownlog.domain

import java.security.MessageDigest

data class TextNode(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val isPassword: Boolean,
    val inputType: Int,
)

data class ExtractedScreen(
    val signature: String,
    val title: String?,
    val content: String,
    val keptLines: Int,
    val droppedPasswordFields: Int,
    val viewIds: List<String>,
)

object ScreenText {
    const val MAX_LINES = 80
    const val MAX_CHARS = 4_000
    const val MAX_LINE_CHARS = 800

    fun extract(nodes: List<TextNode>, windowTitle: String?): ExtractedScreen {
        val lines = LinkedHashSet<String>()
        val viewIds = LinkedHashSet<String>()
        var droppedPasswords = 0
        val titleNorm = windowTitle?.let(::normalizeLine)
        if (titleNorm != null) lines.add(titleNorm)

        for (node in nodes) {
            if (PasswordSignals.isPassword(node.isPassword, node.inputType)) {
                droppedPasswords++
                continue
            }
            var contributed = false
            for (chunk in listOfNotNull(node.text, node.contentDescription)) {
                val line = normalizeLine(chunk) ?: continue
                lines.add(line)
                contributed = true
            }
            if (contributed && viewIds.size < 20) {
                val id = node.viewId?.trim().orEmpty()
                if (id.isNotEmpty()) viewIds.add(id)
            }
        }

        val used = ArrayList<String>(MAX_LINES)
        val content = StringBuilder()
        for (line in lines) {
            if (used.size >= MAX_LINES) break
            val nextLen = if (content.isEmpty()) line.length else content.length + 1 + line.length
            if (nextLen > MAX_CHARS) break
            if (content.isNotEmpty()) content.append('\n')
            content.append(line)
            used.add(line)
        }
        val body = content.toString()
        val signature = if (used.isEmpty()) "" else signatureOf(used.joinToString("\n"))
        return ExtractedScreen(
            signature = signature,
            title = pickTitle(titleNorm, used),
            content = body,
            keptLines = used.size,
            droppedPasswordFields = droppedPasswords,
            viewIds = viewIds.toList(),
        )
    }

    fun normalizeLine(raw: String): String? {
        val collapsed = raw.replace('\u00a0', ' ').trim().replace(Regex("[ \\t]+"), " ")
        if (collapsed.isEmpty()) return null
        if (collapsed.all { !it.isLetterOrDigit() }) return null
        if (collapsed.length == 1 && collapsed[0].code < 0x2E80) return null
        return if (collapsed.length <= MAX_LINE_CHARS) collapsed else collapsed.take(MAX_LINE_CHARS)
    }

    fun signatureOf(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun pickTitle(windowTitle: String?, lines: List<String>): String? {
        if (windowTitle != null && windowTitle.length <= 80) return windowTitle
        return lines.firstOrNull { it.length <= 80 } ?: lines.firstOrNull()?.take(80)
    }
}
