package dev.owner.ownlog.data

import dev.owner.ownlog.domain.FailureAction
import dev.owner.ownlog.domain.SizedItem
import dev.owner.ownlog.domain.encodeBatch
import dev.owner.ownlog.domain.estimateOutgoingBytes
import dev.owner.ownlog.domain.failureAction
import dev.owner.ownlog.domain.planBatches
import java.util.ArrayDeque

sealed class UploadRunResult {
    data object Idle : UploadRunResult()
    data object Done : UploadRunResult()
    data class Retry(val message: String) : UploadRunResult()
    data class Stop(val message: String) : UploadRunResult()
}

class Uploader(
    private val dao: EventDao,
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
        var processed = 0
        var uploadedAny = false
        while (processed < 800) {
            val pending = dao.pending(limit = 200)
            if (pending.isEmpty()) {
                if (uploadedAny) {
                    dao.pruneUploadedBefore(System.currentTimeMillis() - RETAIN_UPLOADED_MS)
                    settingsStore.markUploadSuccess(System.currentTimeMillis())
                }
                return UploadRunResult.Done
            }
            val sized = pending.map { SizedItem(it.id, estimateOutgoingBytes(it.toOutgoing())) }
            val batches = planBatches(sized, maxCount = 40, maxBytes = 450_000)
            val byId = pending.associateBy { it.id }
            val queue = ArrayDeque<List<EventEntity>>()
            for (batch in batches) {
                queue.add(batch.map { byId.getValue(it.id) })
            }
            while (queue.isNotEmpty()) {
                val slice = queue.removeFirst()
                val json = encodeBatch(slice.map { it.toOutgoing() }, prepared.userId)
                when (val outcome = api.upload(settings.supabaseUrl, prepared.apiKey, prepared.accessToken, json)) {
                    is UploadOutcome.Success -> {
                        dao.markUploaded(slice.map { it.id })
                        settingsStore.addUploadedBytes(json.toByteArray(Charsets.UTF_8).size.toLong())
                        processed += slice.size
                        uploadedAny = true
                    }
                    is UploadOutcome.Failure -> {
                        if (outcome.config) {
                            settingsStore.markUploadError(outcome.message)
                            return UploadRunResult.Stop(outcome.message)
                        }
                        when (failureAction(slice.size, outcome.retryable)) {
                            FailureAction.RETRY -> {
                                dao.markAttemptFailed(slice.map { it.id }, outcome.message.take(300))
                                settingsStore.markUploadError(outcome.message)
                                return UploadRunResult.Retry(outcome.message)
                            }
                            FailureAction.ABANDON -> {
                                dao.markAbandoned(slice.first().id, outcome.message.take(300))
                                processed += 1
                            }
                            FailureAction.SPLIT -> {
                                val mid = slice.size / 2
                                queue.addFirst(slice.drop(mid))
                                queue.addFirst(slice.take(mid))
                            }
                        }
                    }
                }
            }
        }
        if (uploadedAny) settingsStore.markUploadSuccess(System.currentTimeMillis())
        return UploadRunResult.Done
    }

    private companion object {
        const val RETAIN_UPLOADED_MS = 14L * 24 * 60 * 60 * 1000
    }
}
