package dev.owner.ownlog.data

import java.util.UUID

class EventRepository(
    private val dao: EventDao,
    private val settings: SettingsStore,
    private val nudgeUpload: () -> Unit,
) {
    suspend fun record(
        type: String,
        occurredAt: Long,
        packageName: String?,
        appLabel: String?,
        title: String?,
        content: String?,
        extrasJson: String?,
        deviceId: String,
        id: String = UUID.randomUUID().toString(),
        nudge: Boolean = true,
    ): String {
        val resolvedDevice = deviceId.ifBlank { settings.ensureDeviceId() }
        if (resolvedDevice.isBlank()) return ""
        val now = System.currentTimeMillis()
        dao.insert(
            EventEntity(
                id = id,
                occurredAt = occurredAt,
                type = type,
                packageName = packageName?.take(200)?.blankToNull(),
                appLabel = appLabel?.take(200)?.blankToNull(),
                title = title?.take(500)?.blankToNull(),
                content = content?.take(8_000)?.blankToNull(),
                extrasJson = extrasJson,
                deviceId = resolvedDevice,
                createdAt = now,
                uploaded = false,
                abandoned = false,
                uploadAttempts = 0,
                lastError = null,
            ),
        )
        if (nudge) nudgeUpload()
        return id
    }
}

private fun String.blankToNull(): String? = if (isBlank()) null else this
