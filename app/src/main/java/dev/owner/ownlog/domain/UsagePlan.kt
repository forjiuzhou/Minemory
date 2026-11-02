package dev.owner.ownlog.domain

enum class UsageKind { FOREGROUND, BACKGROUND }

data class UsageTick(
    val packageName: String,
    val kind: UsageKind,
    val at: Long,
)

data class UsagePlan(
    val events: List<SessionEvent>,
    val open: OpenSession?,
    val cursor: Long,
)

private const val FLICKER_MS = 1_500L

fun planUsage(
    ticks: List<UsageTick>,
    open: OpenSession?,
    skipPackage: String,
    newSessionId: () -> String,
): UsagePlan {
    val events = mutableListOf<SessionEvent>()
    var current = open
    var recentlyClosed: OpenSession? = null
    var closedAt = Long.MIN_VALUE
    var cursor = open?.startedAt ?: 0L
    for (tick in ticks.sortedBy { it.at }) {
        if (tick.packageName.isBlank()) continue
        if (tick.at > cursor) cursor = tick.at
        if (tick.packageName == skipPackage) {
            if (tick.kind == UsageKind.FOREGROUND && current != null) {
                events += backgroundEvent(current!!, tick.at)
                recentlyClosed = current
                closedAt = tick.at
                current = null
            }
            continue
        }
        when (tick.kind) {
            UsageKind.FOREGROUND -> {
                val openNow = current
                if (openNow?.packageName == tick.packageName) continue
                val closed = recentlyClosed
                if (closed != null && closed.packageName == tick.packageName && tick.at - closedAt in 0..FLICKER_MS) {
                    if (events.lastOrNull()?.type == "app_bg" && events.last().packageName == tick.packageName) {
                        events.removeAt(events.lastIndex)
                    }
                    current = closed
                    recentlyClosed = null
                    continue
                }
                if (openNow != null) {
                    events += backgroundEvent(openNow, tick.at)
                }
                val id = newSessionId()
                current = OpenSession(tick.packageName, null, null, tick.at, id)
                recentlyClosed = null
                events += SessionEvent(
                    type = "app_fg",
                    packageName = tick.packageName,
                    appLabel = null,
                    title = null,
                    occurredAt = tick.at,
                    content = null,
                    extras = mapOf("session_id" to id, "source" to "usage_stats"),
                )
            }
            UsageKind.BACKGROUND -> {
                val openNow = current ?: continue
                if (openNow.packageName != tick.packageName) continue
                events += backgroundEvent(openNow, tick.at)
                recentlyClosed = openNow
                closedAt = tick.at
                current = null
            }
        }
    }
    return UsagePlan(events, current, cursor)
}

private fun backgroundEvent(current: OpenSession, at: Long): SessionEvent {
    val duration = (at - current.startedAt).coerceAtLeast(0)
    return SessionEvent(
        type = "app_bg",
        packageName = current.packageName,
        appLabel = current.appLabel,
        title = current.title,
        occurredAt = at,
        content = "前台 ${formatDuration(duration)}",
        extras = linkedMapOf(
            "session_id" to current.sessionId,
            "duration_ms" to duration.toString(),
            "started_at" to current.startedAt.toString(),
            "source" to "usage_stats",
        ),
    )
}
