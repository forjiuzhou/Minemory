package dev.owner.ownlog.data

import dev.owner.ownlog.domain.isSupabaseSecretKey
import dev.owner.ownlog.domain.jsonString
import dev.owner.ownlog.domain.projectRoot
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Owner-scoped PostgREST for phone_commands and phone_presence.
 * Uses the signed-in user access token. Never a service role key.
 */
internal class PhoneCommandApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    fun listPending(projectUrl: String, apiKey: String, accessToken: String, userId: String, nowIso: String): String? {
        val url = table(projectUrl, "phone_commands") ?: return null
        url.addQueryParameter("select", "id,action,args,device_id,status,expires_at,started_at")
        url.addQueryParameter("user_id", "eq.$userId")
        url.addQueryParameter("status", "eq.pending")
        url.addQueryParameter("expires_at", "gt.$nowIso")
        url.addQueryParameter("order", "created_at.asc")
        url.addQueryParameter("limit", "1")
        return call(projectUrl, apiKey, accessToken, Request.Builder().url(url.build()).get())
    }

    fun claim(
        projectUrl: String,
        apiKey: String,
        accessToken: String,
        userId: String,
        id: String,
        deviceId: String,
        nowIso: String,
    ): String? {
        val url = table(projectUrl, "phone_commands") ?: return null
        url.addQueryParameter("id", "eq.$id")
        url.addQueryParameter("user_id", "eq.$userId")
        url.addQueryParameter("status", "eq.pending")
        url.addQueryParameter("expires_at", "gt.$nowIso")
        val body = buildString {
            append("{\"status\":\"running\",\"started_at\":").append(jsonString(nowIso))
            append(",\"device_id\":").append(jsonString(deviceId)).append('}')
        }
        return call(
            projectUrl,
            apiKey,
            accessToken,
            Request.Builder().url(url.build()).patch(body.toRequestBody(JSON)),
            prefer = "return=representation",
        )
    }

    fun finish(
        projectUrl: String,
        apiKey: String,
        accessToken: String,
        userId: String,
        id: String,
        status: String,
        resultJson: String,
        finishedIso: String,
    ): Boolean {
        val url = table(projectUrl, "phone_commands") ?: return false
        url.addQueryParameter("id", "eq.$id")
        url.addQueryParameter("user_id", "eq.$userId")
        url.addQueryParameter("status", "eq.running")
        val body = buildString {
            append("{\"status\":").append(jsonString(status))
            append(",\"finished_at\":").append(jsonString(finishedIso))
            append(",\"result\":").append(resultJson).append('}')
        }
        return call(
            projectUrl,
            apiKey,
            accessToken,
            Request.Builder().url(url.build()).patch(body.toRequestBody(JSON)),
            prefer = "return=minimal",
        ) != null
    }

    fun cancelExpired(
        projectUrl: String,
        apiKey: String,
        accessToken: String,
        userId: String,
        nowIso: String,
    ): Boolean {
        val url = table(projectUrl, "phone_commands") ?: return false
        url.addQueryParameter("user_id", "eq.$userId")
        url.addQueryParameter("status", "eq.pending")
        url.addQueryParameter("expires_at", "lt.$nowIso")
        val body = """{"status":"cancelled","finished_at":${jsonString(nowIso)},"result":{"ok":false,"error":"命令已过期"}}"""
        return call(
            projectUrl,
            apiKey,
            accessToken,
            Request.Builder().url(url.build()).patch(body.toRequestBody(JSON)),
            prefer = "return=minimal",
        ) != null
    }

    fun failStale(
        projectUrl: String,
        apiKey: String,
        accessToken: String,
        userId: String,
        startedBeforeIso: String,
        nowIso: String,
    ): Boolean {
        val url = table(projectUrl, "phone_commands") ?: return false
        url.addQueryParameter("user_id", "eq.$userId")
        url.addQueryParameter("status", "eq.running")
        url.addQueryParameter("started_at", "lt.$startedBeforeIso")
        val body = """{"status":"failed","finished_at":${jsonString(nowIso)},"result":{"ok":false,"error":"执行超时"}}"""
        return call(
            projectUrl,
            apiKey,
            accessToken,
            Request.Builder().url(url.build()).patch(body.toRequestBody(JSON)),
            prefer = "return=minimal",
        ) != null
    }

    fun upsertPresence(
        projectUrl: String,
        apiKey: String,
        accessToken: String,
        userId: String,
        deviceId: String,
        a11yConnected: Boolean,
        remoteEnabled: Boolean,
        foregroundPackage: String?,
        nowIso: String,
    ): Boolean {
        val url = table(projectUrl, "phone_presence") ?: return false
        val body = buildString {
            append("{\"user_id\":").append(jsonString(userId))
            append(",\"device_id\":").append(jsonString(deviceId))
            append(",\"a11y_connected\":").append(a11yConnected)
            append(",\"remote_enabled\":").append(remoteEnabled)
            append(",\"foreground_package\":")
            append(if (foregroundPackage.isNullOrBlank()) "null" else jsonString(foregroundPackage))
            append(",\"last_seen\":").append(jsonString(nowIso)).append('}')
        }
        return call(
            projectUrl,
            apiKey,
            accessToken,
            Request.Builder().url(url.build()).post(body.toRequestBody(JSON)),
            prefer = "resolution=merge-duplicates,return=minimal",
        ) != null
    }

    private fun table(projectUrl: String, name: String): okhttp3.HttpUrl.Builder? {
        val root = projectRoot(projectUrl).trimEnd('/')
        return "$root/rest/v1/$name".toHttpUrlOrNull()?.newBuilder()
    }

    private fun call(
        projectUrl: String,
        apiKey: String,
        accessToken: String,
        builder: Request.Builder,
        prefer: String? = null,
    ): String? {
        if (projectUrl.isBlank() || apiKey.isBlank() || accessToken.isBlank()) return null
        if (isSupabaseSecretKey(apiKey)) return null
        builder.header("apikey", apiKey.trim())
        builder.header("Authorization", "Bearer ${accessToken.trim()}")
        builder.header("Accept", "application/json")
        builder.header("User-Agent", "OwnLog/1.0")
        if (prefer != null) builder.header("Prefer", prefer)
        return try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) text else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
