package dev.owner.ownlog.domain

data class ClickSnap(
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val isPassword: Boolean,
    val inputType: Int,
)

data class ClickDecision(
    val record: Boolean,
    val content: String?,
    val passwordSkipped: Boolean,
)

fun decideClick(snap: ClickSnap): ClickDecision {
    if (PasswordSignals.isPassword(snap.isPassword, snap.inputType)) {
        return ClickDecision(record = true, content = null, passwordSkipped = true)
    }
    val content = listOf(snap.text, snap.contentDescription)
        .mapNotNull { raw ->
            raw?.replace('\u00a0', ' ')?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }
        }
        .distinct()
        .joinToString(" · ")
        .take(300)
    if (content.isBlank()) return ClickDecision(record = false, content = null, passwordSkipped = false)
    return ClickDecision(record = true, content = content, passwordSkipped = false)
}
