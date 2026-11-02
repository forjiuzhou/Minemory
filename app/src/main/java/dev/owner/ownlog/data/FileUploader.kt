package dev.owner.ownlog.data

import java.io.File

class FileRepository(
    private val dao: FileDao,
    private val nudgeUpload: () -> Unit,
) {
    suspend fun enqueue(eventId: String, localPath: String, objectPath: String, mime: String, bytes: Long) {
        dao.insert(
            FileEntity(
                id = eventId,
                eventId = eventId,
                localPath = localPath,
                objectPath = objectPath,
                mime = mime,
                bytes = bytes,
                uploaded = false,
                abandoned = false,
                uploadAttempts = 0,
                lastError = null,
                createdAt = System.currentTimeMillis(),
            ),
        )
        nudgeUpload()
    }
}

class FileUploader(
    private val dao: FileDao,
    private val settingsStore: SettingsStore,
    private val auth: AuthGate,
    private val binder: OwnerBinder,
    private val api: SupabaseApi = SupabaseApi(),
) {
    suspend fun runOnce(): UploadRunResult {
        val settings = settingsStore.get()
        if (settings.supabaseUrl.isBlank() || settings.supabaseAnonKey.isBlank()) {
            return UploadRunResult.Idle
        }
        val prepared = when (val ready = auth.prepare()) {
            is PrepareResult.Ready -> ready
            is PrepareResult.Paused -> return UploadRunResult.Stop(ready.message)
            is PrepareResult.Retry -> return UploadRunResult.Retry(ready.message)
        }
        binder.bind(prepared.userId)
        val pending = dao.pending(limit = 20)
        if (pending.isEmpty()) return UploadRunResult.Done
        var uploadedAny = false
        for (file in pending) {
            val local = File(file.localPath)
            if (!local.exists() || local.length() <= 0L) {
                dao.markAbandoned(file.id, "本地文件不见了")
                continue
            }
            when (val outcome = api.uploadObject(
                settings.supabaseUrl,
                prepared.apiKey,
                prepared.accessToken,
                file.objectPath,
                file.mime,
                local,
            )) {
                UploadOutcome.Success -> {
                    dao.markUploaded(file.id)
                    settingsStore.addUploadedBytes(local.length().takeIf { it > 0L } ?: file.bytes)
                    local.delete()
                    uploadedAny = true
                }
                is UploadOutcome.Failure -> {
                    if (outcome.config) {
                        settingsStore.markUploadError(outcome.message)
                        return UploadRunResult.Stop(outcome.message)
                    }
                    val attempts = file.uploadAttempts + 1
                    if (!outcome.retryable && attempts >= 5) {
                        dao.markAbandoned(file.id, outcome.message.take(300))
                        continue
                    }
                    dao.markFailed(file.id, outcome.message.take(300))
                    settingsStore.markUploadError(outcome.message)
                    return UploadRunResult.Retry(outcome.message)
                }
            }
        }
        if (uploadedAny) settingsStore.markUploadSuccess(System.currentTimeMillis())
        return UploadRunResult.Done
    }
}
