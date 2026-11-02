package dev.owner.ownlog.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "files")
data class FileEntity(
    @PrimaryKey val id: String,
    val eventId: String,
    val localPath: String,
    val objectPath: String,
    val mime: String,
    val bytes: Long,
    val uploaded: Boolean,
    val abandoned: Boolean,
    val uploadAttempts: Int,
    val lastError: String?,
    val createdAt: Long,
)
