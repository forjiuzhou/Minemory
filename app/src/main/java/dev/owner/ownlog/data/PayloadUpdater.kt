package dev.owner.ownlog.data

import dev.owner.ownlog.domain.encodeEventPatch
import dev.owner.ownlog.domain.extrasWithPageMeta
import dev.owner.ownlog.domain.mergeJson
import dev.owner.ownlog.domain.PageMeta
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PayloadUpdater(
    private val dao: EventDao,
    private val settingsStore: SettingsStore,
    private val auth: AuthGate,
    private val api: SupabaseApi = SupabaseApi(),
) {
    private val gate = Mutex()

    suspend fun mergeFields(id: String, fields: Map<String, String?>) = gate.withLock {
        val row = dao.find(id) ?: return@withLock
        val extras = mergeJson(row.extrasJson, fields)
        dao.updatePayload(id, row.title, row.content, extras)
        patchIfUploaded(id, title = null, content = null, extrasJson = extras)
    }

    suspend fun applyPageMeta(id: String, meta: PageMeta) = gate.withLock {
        val row = dao.find(id) ?: return@withLock
        val extras = extrasWithPageMeta(row.extrasJson, meta)
        if (extras == row.extrasJson) return@withLock
        dao.updatePayload(id, row.title, row.content, extras)
        patchIfUploaded(id, title = null, content = null, extrasJson = extras)
    }

    suspend fun replaceContent(id: String, content: String, title: String? = null) = gate.withLock {
        val row = dao.find(id) ?: return@withLock
        val nextTitle = title ?: row.title
        dao.updatePayload(id, nextTitle, content, row.extrasJson)
        patchIfUploaded(id, title = nextTitle, content = content, extrasJson = null)
    }

    private suspend fun patchIfUploaded(id: String, title: String?, content: String?, extrasJson: String?) {
        val fresh = dao.find(id) ?: return
        if (!fresh.uploaded) return
        val settings = settingsStore.get()
        if (settings.supabaseUrl.isBlank() || settings.supabaseAnonKey.isBlank()) return
        val body = encodeEventPatch(title = title, content = content, extrasJson = extrasJson)
        if (body == "{}") return
        val prepared = when (val ready = auth.prepare()) {
            is PrepareResult.Ready -> ready
            else -> return
        }
        api.patchEvent(settings.supabaseUrl, prepared.apiKey, prepared.accessToken, id, body)
    }
}
