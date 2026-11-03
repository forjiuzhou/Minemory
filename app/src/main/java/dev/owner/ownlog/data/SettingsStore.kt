package dev.owner.ownlog.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.owner.ownlog.BuildConfig
import dev.owner.ownlog.domain.DEFAULT_OCR_PACKAGES
import dev.owner.ownlog.domain.OcrPolicy
import dev.owner.ownlog.domain.parsePackageList
import dev.owner.ownlog.domain.startOfDayMillis
import dev.owner.ownlog.domain.upgradeDefaultOcrPackages
import java.io.IOException
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.settingsDataStore by preferencesDataStore(name = "ownlog_settings")

data class Settings(
    val supabaseUrl: String = "",
    val supabaseAnonKey: String = "",
    val deviceId: String = "",
    val enableUsage: Boolean = true,
    val enableUiText: Boolean = true,
    val enableTap: Boolean = true,
    val enableEngagement: Boolean = true,
    val enableNotification: Boolean = true,
    val enableLocation: Boolean = true,
    val enableOcr: Boolean = true,
    val enableShare: Boolean = true,
    val deepMode: Boolean = true,
    val onboardingDone: Boolean = false,
    val uploadWifiOnly: Boolean = false,
    val ocrPackagesRaw: String = DEFAULT_OCR_PACKAGES,
    val interactionOcrGapMs: Long = OcrPolicy.DEFAULT_INTERACTION_GAP_MS,
    val uploadedBytesToday: Long = 0L,
    val uploadedBytesDay: Long = 0L,
    val lastUploadAt: Long? = null,
    val lastUploadError: String? = null,
    val lastCollectorError: String? = null,
) {
    val ocrPackages: Set<String> get() = parsePackageList(ocrPackagesRaw)
}

class SettingsStore(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore
    private val cache = MutableStateFlow(Settings())
    val flow: StateFlow<Settings> = cache.asStateFlow()

    fun get(): Settings = cache.value

    suspend fun warm() {
        ensureDeviceId()
        cache.value = dataStore.data.first().toSettings()
    }

    fun startCollecting(scope: CoroutineScope) {
        scope.launch {
            dataStore.data
                .catch { error ->
                    if (error is IOException) emit(emptyPreferences()) else throw error
                }
                .collect { cache.value = it.toSettings() }
        }
    }

    suspend fun ensureDeviceId(): String {
        val existing = dataStore.data.first()[Keys.DEVICE_ID]
        if (!existing.isNullOrBlank()) {
            if (cache.value.deviceId != existing) {
                cache.value = cache.value.copy(deviceId = existing)
            }
            return existing
        }
        val created = UUID.randomUUID().toString()
        var result = created
        dataStore.edit { prefs ->
            val current = prefs[Keys.DEVICE_ID]
            if (current.isNullOrBlank()) {
                prefs[Keys.DEVICE_ID] = created
            } else {
                result = current
            }
        }
        cache.value = cache.value.copy(deviceId = result)
        return result
    }

    suspend fun saveConnection(url: String, anonKey: String, ocrPackagesRaw: String) {
        val cleanUrl = url.trim()
        val cleanKey = anonKey.trim()
        val packages = ocrPackagesRaw.trim().ifBlank { DEFAULT_OCR_PACKAGES }
        cache.value = cache.value.copy(
            supabaseUrl = cleanUrl.ifBlank { BuildConfig.SUPABASE_URL },
            supabaseAnonKey = cleanKey.ifBlank { BuildConfig.SUPABASE_ANON_KEY },
            ocrPackagesRaw = packages,
        )
        dataStore.edit { prefs ->
            prefs[Keys.URL] = cleanUrl
            prefs[Keys.ANON_KEY] = cleanKey
            prefs[Keys.OCR_PACKAGES] = packages
        }
    }

    suspend fun setUsage(enabled: Boolean) = setFlag(Keys.USAGE, enabled) { copy(enableUsage = enabled) }
    suspend fun setUiText(enabled: Boolean) = setFlag(Keys.UI_TEXT, enabled) { copy(enableUiText = enabled) }
    suspend fun setTap(enabled: Boolean) = setFlag(Keys.TAP, enabled) { copy(enableTap = enabled) }
    suspend fun setEngagement(enabled: Boolean) = setFlag(Keys.ENGAGEMENT, enabled) { copy(enableEngagement = enabled) }
    suspend fun setNotification(enabled: Boolean) = setFlag(Keys.NOTIFICATION, enabled) { copy(enableNotification = enabled) }
    suspend fun setLocationEnabled(enabled: Boolean) = setFlag(Keys.LOCATION, enabled) { copy(enableLocation = enabled) }
    suspend fun setOcrEnabled(enabled: Boolean) = setFlag(Keys.OCR, enabled) { copy(enableOcr = enabled) }

    suspend fun setInteractionOcrGapMs(ms: Long) {
        val clamped = OcrPolicy.clampInteractionGap(ms)
        cache.value = cache.value.copy(interactionOcrGapMs = clamped)
        dataStore.edit { prefs -> prefs[Keys.INTERACTION_OCR_GAP] = clamped }
    }

    suspend fun addUploadedBytes(bytes: Long, now: Long = System.currentTimeMillis()) {
        if (bytes <= 0L) return
        val day = startOfDayMillis(now, ZoneId.systemDefault())
        var total = bytes
        dataStore.edit { prefs ->
            val storedDay = prefs[Keys.UPLOADED_BYTES_DAY] ?: 0L
            val base = if (storedDay == day) prefs[Keys.UPLOADED_BYTES] ?: 0L else 0L
            total = base + bytes
            prefs[Keys.UPLOADED_BYTES] = total
            prefs[Keys.UPLOADED_BYTES_DAY] = day
        }
        cache.value = cache.value.copy(uploadedBytesToday = total, uploadedBytesDay = day)
    }
    suspend fun setShareEnabled(enabled: Boolean) = setFlag(Keys.SHARE, enabled) { copy(enableShare = enabled) }
    suspend fun setDeepMode(enabled: Boolean) = setFlag(Keys.DEEP, enabled) { copy(deepMode = enabled) }

    suspend fun setOnboardingDone() {
        if (cache.value.onboardingDone) return
        cache.value = cache.value.copy(onboardingDone = true)
        dataStore.edit { prefs -> prefs[Keys.ONBOARDING] = true }
    }
    suspend fun setUploadWifiOnly(enabled: Boolean) = setFlag(Keys.WIFI_ONLY, enabled) { copy(uploadWifiOnly = enabled) }

    suspend fun markUploadSuccess(at: Long) {
        cache.value = cache.value.copy(lastUploadAt = at, lastUploadError = null)
        dataStore.edit { prefs ->
            prefs[Keys.LAST_UPLOAD_AT] = at
            prefs.remove(Keys.LAST_UPLOAD_ERROR)
        }
    }

    suspend fun markUploadError(message: String) {
        val clipped = message.take(400)
        cache.value = cache.value.copy(lastUploadError = clipped)
        dataStore.edit { prefs -> prefs[Keys.LAST_UPLOAD_ERROR] = clipped }
    }

    suspend fun clearUploadError() {
        if (cache.value.lastUploadError == null) return
        cache.value = cache.value.copy(lastUploadError = null)
        dataStore.edit { prefs -> prefs.remove(Keys.LAST_UPLOAD_ERROR) }
    }

    suspend fun setCollectorError(message: String?) {
        val clipped = message?.take(400)
        if (cache.value.lastCollectorError == clipped) return
        cache.value = cache.value.copy(lastCollectorError = clipped)
        dataStore.edit { prefs ->
            if (clipped.isNullOrBlank()) prefs.remove(Keys.LAST_COLLECTOR_ERROR)
            else prefs[Keys.LAST_COLLECTOR_ERROR] = clipped
        }
    }

    private suspend fun setFlag(
        key: Preferences.Key<Boolean>,
        enabled: Boolean,
        update: Settings.() -> Settings,
    ) {
        cache.value = cache.value.update()
        dataStore.edit { prefs -> prefs[key] = enabled }
    }

    private fun Preferences.toSettings(): Settings {
        val storedUrl = this[Keys.URL].orEmpty()
        val storedKey = this[Keys.ANON_KEY].orEmpty()
        val uploadAt = this[Keys.LAST_UPLOAD_AT]
        return Settings(
            supabaseUrl = storedUrl.ifBlank { BuildConfig.SUPABASE_URL },
            supabaseAnonKey = storedKey.ifBlank { BuildConfig.SUPABASE_ANON_KEY },
            deviceId = this[Keys.DEVICE_ID].orEmpty(),
            enableUsage = this[Keys.USAGE] ?: this[Keys.APP_SWITCH] ?: true,
            enableUiText = this[Keys.UI_TEXT] ?: true,
            enableTap = this[Keys.TAP] ?: true,
            enableEngagement = this[Keys.ENGAGEMENT] ?: true,
            enableNotification = this[Keys.NOTIFICATION] ?: true,
            enableLocation = this[Keys.LOCATION] ?: true,
            enableOcr = this[Keys.OCR] ?: true,
            enableShare = this[Keys.SHARE] ?: true,
            deepMode = this[Keys.DEEP] ?: true,
            onboardingDone = this[Keys.ONBOARDING] ?: false,
            uploadWifiOnly = this[Keys.WIFI_ONLY] ?: false,
            ocrPackagesRaw = upgradeDefaultOcrPackages(this[Keys.OCR_PACKAGES]),
            interactionOcrGapMs = OcrPolicy.clampInteractionGap(
                this[Keys.INTERACTION_OCR_GAP]?.takeIf { it > 0L } ?: OcrPolicy.DEFAULT_INTERACTION_GAP_MS,
            ),
            uploadedBytesToday = this[Keys.UPLOADED_BYTES] ?: 0L,
            uploadedBytesDay = this[Keys.UPLOADED_BYTES_DAY] ?: 0L,
            lastUploadAt = uploadAt?.takeIf { it > 0L },
            lastUploadError = this[Keys.LAST_UPLOAD_ERROR],
            lastCollectorError = this[Keys.LAST_COLLECTOR_ERROR],
        )
    }

    private object Keys {
        val URL = stringPreferencesKey("supabase_url")
        val ANON_KEY = stringPreferencesKey("supabase_anon_key")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val APP_SWITCH = booleanPreferencesKey("enable_app_switch")
        val USAGE = booleanPreferencesKey("enable_usage")
        val UI_TEXT = booleanPreferencesKey("enable_ui_text")
        val TAP = booleanPreferencesKey("enable_tap")
        val ENGAGEMENT = booleanPreferencesKey("enable_engagement")
        val NOTIFICATION = booleanPreferencesKey("enable_notification")
        val LOCATION = booleanPreferencesKey("enable_location")
        val OCR = booleanPreferencesKey("enable_ocr")
        val SHARE = booleanPreferencesKey("enable_share")
        val DEEP = booleanPreferencesKey("deep_mode")
        val ONBOARDING = booleanPreferencesKey("onboarding_done")
        val WIFI_ONLY = booleanPreferencesKey("upload_wifi_only")
        val OCR_PACKAGES = stringPreferencesKey("ocr_packages")
        val INTERACTION_OCR_GAP = longPreferencesKey("interaction_ocr_gap_ms")
        val UPLOADED_BYTES = longPreferencesKey("uploaded_bytes_today")
        val UPLOADED_BYTES_DAY = longPreferencesKey("uploaded_bytes_day")
        val LAST_UPLOAD_AT = longPreferencesKey("last_upload_at")
        val LAST_UPLOAD_ERROR = stringPreferencesKey("last_upload_error")
        val LAST_COLLECTOR_ERROR = stringPreferencesKey("last_collector_error")
    }
}
