package dev.owner.ownlog.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.domain.UsageKind
import dev.owner.ownlog.domain.UsageTick
import dev.owner.ownlog.domain.extrasFromMap
import dev.owner.ownlog.domain.planUsage
import java.util.UUID

internal object UsageCollector {
    private const val LOOKBACK_MS = 6L * 60 * 60 * 1000
    private const val FOREGROUND = 1
    private const val BACKGROUND = 2

    suspend fun collect(context: Context, app: OwnLogApp) {
        val stats = context.getSystemService(UsageStatsManager::class.java) ?: return
        val end = System.currentTimeMillis()
        val stored = UsagePrefs.cursor(context)
        val start = if (stored in 1 until end) stored else end - LOOKBACK_MS
        val queried = try {
            stats.queryEvents(start, end)
        } catch (_: SecurityException) {
            return
        } ?: return
        val ticks = ArrayList<UsageTick>()
        val event = UsageEvents.Event()
        while (queried.hasNextEvent()) {
            queried.getNextEvent(event)
            val packageName = event.packageName ?: continue
            val kind = when (event.eventType) {
                FOREGROUND -> UsageKind.FOREGROUND
                BACKGROUND -> UsageKind.BACKGROUND
                else -> null
            } ?: continue
            ticks += UsageTick(packageName, kind, event.timeStamp)
        }
        val plan = planUsage(
            ticks = ticks,
            open = UsagePrefs.loadOpen(context),
            skipPackage = context.packageName,
            newSessionId = { UUID.randomUUID().toString() },
        )
        val deviceId = app.settings.ensureDeviceId()
        for (item in plan.events) {
            app.repository.record(
                type = item.type,
                occurredAt = item.occurredAt,
                packageName = item.packageName,
                appLabel = AppLabels.label(context, item.packageName),
                title = item.title,
                content = item.content,
                extrasJson = extrasFromMap(item.extras),
                deviceId = deviceId,
            )
        }
        UsagePrefs.saveOpen(context, plan.open)
        UsagePrefs.saveCursor(context, end)
    }
}
