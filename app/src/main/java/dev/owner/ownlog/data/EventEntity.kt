package dev.owner.ownlog.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.owner.ownlog.domain.OutgoingEvent

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["uploaded", "abandoned", "occurredAt"]),
        Index(value = ["occurredAt"]),
    ],
)
data class EventEntity(
    @PrimaryKey val id: String,
    val occurredAt: Long,
    val type: String,
    val packageName: String?,
    val appLabel: String?,
    val title: String?,
    val content: String?,
    val extrasJson: String?,
    val deviceId: String,
    val createdAt: Long,
    val uploaded: Boolean,
    val abandoned: Boolean,
    val uploadAttempts: Int,
    val lastError: String?,
)

fun EventEntity.toOutgoing(): OutgoingEvent {
    return OutgoingEvent(
        id = id,
        occurredAtMillis = occurredAt,
        type = type,
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        content = content,
        extrasJson = extrasJson,
        deviceId = deviceId,
        createdAtMillis = createdAt,
    )
}
