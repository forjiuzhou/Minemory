package dev.owner.ownlog.data

import dev.owner.ownlog.domain.AUTH_REFRESH_FAILED
import dev.owner.ownlog.domain.AUTH_SIGNED_OUT
import dev.owner.ownlog.domain.shouldRefresh
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class PrepareResult {
    data class Ready(val apiKey: String, val accessToken: String, val userId: String) : PrepareResult()
    data class Paused(val message: String) : PrepareResult()
    data class Retry(val message: String) : PrepareResult()
}

class AuthGate(
    private val settingsStore: SettingsStore,
    private val store: AuthStore,
    private val api: SupabaseApi,
) {
    private val mutex = Mutex()

    suspend fun signInWithPassword(email: String, password: String): String? = mutex.withLock {
        val settings = settingsStore.get()
        val now = System.currentTimeMillis()
        return@withLock when (val call = api.signInWithPassword(settings.supabaseUrl, settings.supabaseAnonKey, email, password, now)) {
            is AuthCall.Success -> {
                store.save(call.session, email)
                settingsStore.clearUploadError()
                null
            }
            is AuthCall.Failure -> call.message
        }
    }

    suspend fun sendEmailCode(email: String): String? {
        val settings = settingsStore.get()
        return when (val outcome = api.sendEmailCode(settings.supabaseUrl, settings.supabaseAnonKey, email)) {
            UploadOutcome.Success -> null
            is UploadOutcome.Failure -> outcome.message
        }
    }

    suspend fun verifyEmailCode(email: String, code: String): String? = mutex.withLock {
        val settings = settingsStore.get()
        val now = System.currentTimeMillis()
        return@withLock when (val call = api.verifyEmailCode(settings.supabaseUrl, settings.supabaseAnonKey, email, code, now)) {
            is AuthCall.Success -> {
                store.save(call.session, email)
                settingsStore.clearUploadError()
                null
            }
            is AuthCall.Failure -> call.message
        }
    }

    suspend fun signOut() {
        store.clear()
        settingsStore.markUploadError(AUTH_SIGNED_OUT)
    }

    suspend fun prepare(): PrepareResult = mutex.withLock {
        val settings = settingsStore.get()
        if (settings.supabaseUrl.isBlank() || settings.supabaseAnonKey.isBlank()) {
            return@withLock PrepareResult.Paused("还没有填写 Supabase URL 和 API key")
        }
        if (store.flow.value.secureStoreFailed) {
            val message = "这台手机无法加密保存登录状态，上传已暂停。"
            settingsStore.markUploadError(message)
            return@withLock PrepareResult.Paused(message)
        }
        val session = store.current()
        if (session == null) {
            settingsStore.markUploadError(AUTH_SIGNED_OUT)
            return@withLock PrepareResult.Paused(AUTH_SIGNED_OUT)
        }
        val now = System.currentTimeMillis()
        if (!shouldRefresh(session.expiresAtMillis, now)) {
            return@withLock PrepareResult.Ready(settings.supabaseAnonKey, session.accessToken, session.userId)
        }
        when (val call = api.refreshSession(settings.supabaseUrl, settings.supabaseAnonKey, session.refreshToken, now)) {
            is AuthCall.Success -> {
                val saved = session.copy(
                    accessToken = call.session.accessToken,
                    refreshToken = call.session.refreshToken,
                    expiresAtMillis = call.session.expiresAtMillis,
                    userId = call.session.userId,
                    email = call.session.email ?: session.email,
                )
                store.saveStored(saved)
                PrepareResult.Ready(settings.supabaseAnonKey, saved.accessToken, saved.userId)
            }
            is AuthCall.Failure -> {
                if (call.invalid) {
                    store.clear()
                    settingsStore.markUploadError(AUTH_REFRESH_FAILED)
                    PrepareResult.Paused(AUTH_REFRESH_FAILED)
                } else {
                    settingsStore.markUploadError("登录状态暂时无法刷新，上传已暂停。")
                    if (call.retryable) {
                        PrepareResult.Retry(call.message)
                    } else {
                        PrepareResult.Paused(call.message)
                    }
                }
            }
        }
    }
}
