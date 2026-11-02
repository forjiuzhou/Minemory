package dev.owner.ownlog.domain

data class NotificationSnap(
    val key: String,
    val packageName: String,
    val title: String?,
    val content: String?,
    val subText: String?,
    val channelId: String?,
    val category: String?,
    val notificationId: Int,
    val ongoing: Boolean,
    val postedAt: Long,
)

class NotificationDedupe(
    private val sameTextWindowMs: Long = 120_000L,
    private val minIntervalMs: Long = 10_000L,
) {
    private data class Prev(val signature: String, val at: Long)

    private val previous = HashMap<String, Prev>()

    fun shouldRecord(key: String, textSignature: String, now: Long): Boolean {
        if (textSignature.isBlank()) return false
        val prior = previous[key]
        if (prior != null) {
            if (prior.signature == textSignature && now - prior.at < sameTextWindowMs) return false
            if (prior.signature != textSignature && now - prior.at < minIntervalMs) return false
        }
        previous[key] = Prev(textSignature, now)
        if (previous.size > 200) {
            val cutoff = now - sameTextWindowMs
            previous.entries.removeIf { it.value.at < cutoff }
        }
        return true
    }
}
