package dev.owner.ownlog.data

import dev.owner.ownlog.domain.objectKey

class OwnerBinder(
    private val files: FileDao,
    private val payloads: PayloadUpdater,
) {
    suspend fun bind(userId: String) {
        val pending = files.pending(limit = 500)
        for (file in pending) {
            val key = objectKey(userId, file.objectPath)
            if (key == file.objectPath) continue
            files.updatePath(file.id, key)
            payloads.mergeFields(file.eventId, mapOf("file_path" to key))
        }
    }
}
