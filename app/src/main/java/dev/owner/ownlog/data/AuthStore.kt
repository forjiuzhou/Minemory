package dev.owner.ownlog.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.owner.ownlog.domain.ParsedAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val userId: String,
    val email: String,
)

data class AuthSnapshot(
    val signedIn: Boolean = false,
    val email: String = "",
    val userId: String = "",
    val secureStoreFailed: Boolean = false,
)

class AuthStore(context: Context) {
    private val prefs: SharedPreferences? = open(context.applicationContext)
    private val state = MutableStateFlow(read())
    val flow: StateFlow<AuthSnapshot> = state.asStateFlow()

    fun current(): StoredSession? = load()

    fun userId(): String? = load()?.userId?.ifBlank { null }

    fun save(session: ParsedAuth, emailFallback: String) {
        val prefs = prefs ?: return
        val email = session.email?.ifBlank { null } ?: emailFallback.trim()
        prefs.edit()
            .putString(ACCESS, session.accessToken)
            .putString(REFRESH, session.refreshToken)
            .putLong(EXPIRES, session.expiresAtMillis)
            .putString(USER, session.userId)
            .putString(EMAIL, email)
            .commit()
        state.value = AuthSnapshot(signedIn = true, email = email, userId = session.userId)
    }

    fun saveStored(session: StoredSession) {
        val prefs = prefs ?: return
        prefs.edit()
            .putString(ACCESS, session.accessToken)
            .putString(REFRESH, session.refreshToken)
            .putLong(EXPIRES, session.expiresAtMillis)
            .putString(USER, session.userId)
            .putString(EMAIL, session.email)
            .commit()
        state.value = AuthSnapshot(signedIn = true, email = session.email, userId = session.userId)
    }

    fun clear() {
        prefs?.edit()?.clear()?.commit()
        state.value = AuthSnapshot(secureStoreFailed = prefs == null)
    }

    private fun read(): AuthSnapshot {
        if (prefs == null) return AuthSnapshot(secureStoreFailed = true)
        val session = load() ?: return AuthSnapshot()
        return AuthSnapshot(signedIn = true, email = session.email, userId = session.userId)
    }

    private fun load(): StoredSession? {
        val prefs = prefs ?: return null
        val access = prefs.getString(ACCESS, null)?.trim().orEmpty()
        val refresh = prefs.getString(REFRESH, null)?.trim().orEmpty()
        val user = prefs.getString(USER, null)?.trim().orEmpty()
        if (access.isBlank() || refresh.isBlank() || user.isBlank()) return null
        return StoredSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = prefs.getLong(EXPIRES, 0L),
            userId = user,
            email = prefs.getString(EMAIL, null).orEmpty(),
        )
    }

    private companion object {
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
        const val EXPIRES = "expires_at"
        const val USER = "user_id"
        const val EMAIL = "email"

        fun open(context: Context): SharedPreferences? {
            return try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    "ownlog_session",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
