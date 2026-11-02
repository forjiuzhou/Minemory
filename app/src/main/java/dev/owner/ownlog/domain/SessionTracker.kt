package dev.owner.ownlog.domain

data class OpenSession(
    val packageName: String,
    val appLabel: String?,
    val title: String?,
    val startedAt: Long,
    val sessionId: String,
)

data class SessionEvent(
    val type: String,
    val packageName: String,
    val appLabel: String?,
    val title: String?,
    val occurredAt: Long,
    val content: String?,
    val extras: Map<String, String>,
)

class SessionTracker(initial: OpenSession? = null) {
    var open: OpenSession? = initial
        private set

    fun restore(session: OpenSession) {
        open = session
    }

    fun discard() {
        open = null
    }

    fun onForeground(
        packageName: String,
        appLabel: String?,
        title: String?,
        now: Long,
        newSessionId: () -> String,
    ): List<SessionEvent> {
        val current = open
        if (current?.packageName == packageName) {
            if (!title.isNullOrBlank() && title != current.title) {
                open = current.copy(title = title, appLabel = appLabel ?: current.appLabel)
            }
            return emptyList()
        }
        val events = mutableListOf<SessionEvent>()
        if (current != null) {
            events += backgroundEvent(current, now, reason = null, interrupted = false)
        }
        val id = newSessionId()
        open = OpenSession(packageName, appLabel, title, now, id)
        events += SessionEvent(
            type = "app_fg",
            packageName = packageName,
            appLabel = appLabel,
            title = title,
            occurredAt = now,
            content = null,
            extras = mapOf("session_id" to id),
        )
        return events
    }

    fun close(now: Long, reason: String, interrupted: Boolean = false): List<SessionEvent> {
        val current = open ?: return emptyList()
        open = null
        return listOf(backgroundEvent(current, now, reason, interrupted))
    }

    private fun backgroundEvent(
        current: OpenSession,
        now: Long,
        reason: String?,
        interrupted: Boolean,
    ): SessionEvent {
        val duration = (now - current.startedAt).coerceAtLeast(0)
        val extras = linkedMapOf(
            "session_id" to current.sessionId,
            "duration_ms" to duration.toString(),
            "started_at" to current.startedAt.toString(),
        )
        if (reason != null) extras["reason"] = reason
        if (interrupted) extras["interrupted"] = "true"
        return SessionEvent(
            type = "app_bg",
            packageName = current.packageName,
            appLabel = current.appLabel,
            title = current.title,
            occurredAt = now,
            content = "前台 ${formatDuration(duration)}",
            extras = extras,
        )
    }
}

fun formatDuration(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}
