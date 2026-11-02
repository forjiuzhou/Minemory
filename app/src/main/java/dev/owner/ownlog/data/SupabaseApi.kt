package dev.owner.ownlog.data

import dev.owner.ownlog.domain.AUTH_SIGNED_OUT
import dev.owner.ownlog.domain.ParsedAuth
import dev.owner.ownlog.domain.SUPABASE_SECRET_KEY_ERROR
import dev.owner.ownlog.domain.isSupabaseSecretKey
import dev.owner.ownlog.domain.jsonString
import dev.owner.ownlog.domain.normalizeSupabaseEndpoint
import dev.owner.ownlog.domain.parseAuthResponse
import dev.owner.ownlog.domain.projectRoot
import dev.owner.ownlog.domain.storageObjectUrl
import dev.owner.ownlog.domain.supabaseAuthorizationHeader
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

sealed class UploadOutcome {
    data object Success : UploadOutcome()
    data class Failure(
        val code: Int?,
        val message: String,
        val retryable: Boolean,
        val config: Boolean,
    ) : UploadOutcome()
}

sealed class AuthCall {
    data class Success(val session: ParsedAuth) : AuthCall()
    data class Failure(val message: String, val invalid: Boolean, val retryable: Boolean) : AuthCall()
}

class SupabaseApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    fun upload(projectUrl: String, apiKey: String, accessToken: String, jsonArray: String): UploadOutcome {
        val refused = refuse(projectUrl, apiKey, accessToken) ?: return postJson(
            normalizeSupabaseEndpoint(projectUrl),
            apiKey,
            accessToken,
            jsonArray,
            extraHeaders = listOf("Prefer" to "resolution=ignore-duplicates,return=minimal"),
        )
        return refused
    }

    fun ping(projectUrl: String, apiKey: String, accessToken: String): UploadOutcome {
        val refused = refuse(projectUrl, apiKey, accessToken)
        if (refused != null) return refused
        val request = try {
            Request.Builder()
                .url(normalizeSupabaseEndpoint(projectUrl) + "?select=id&limit=1")
                .applyUser(apiKey, accessToken)
                .header("Accept", "application/json")
                .header("User-Agent", "OwnLog/1.0")
                .get()
                .build()
        } catch (error: IllegalArgumentException) {
            return UploadOutcome.Failure(null, "Supabase URL 无法解析", retryable = false, config = true)
        }
        return execute(request)
    }

    fun uploadObject(
        projectUrl: String,
        apiKey: String,
        accessToken: String,
        objectPath: String,
        mime: String,
        file: File,
    ): UploadOutcome {
        val refused = refuse(projectUrl, apiKey, accessToken)
        if (refused != null) return refused
        val request = try {
            val safeMime = mime.substringBefore(';').trim()
            val media = if (safeMime.contains('/') && !safeMime.contains('*')) {
                safeMime.toMediaType()
            } else {
                "application/octet-stream".toMediaType()
            }
            Request.Builder()
                .url(storageObjectUrl(projectUrl, objectPath))
                .applyUser(apiKey, accessToken)
                .header("User-Agent", "OwnLog/1.0")
                .header("x-upsert", "false")
                .post(file.asRequestBody(media))
                .build()
        } catch (error: IllegalArgumentException) {
            return UploadOutcome.Failure(null, "Supabase URL 无法解析", retryable = false, config = true)
        }
        return execute(request)
    }

    fun patchEvent(projectUrl: String, apiKey: String, accessToken: String, id: String, body: String): UploadOutcome {
        val refused = refuse(projectUrl, apiKey, accessToken)
        if (refused != null) return refused
        val request = try {
            Request.Builder()
                .url(normalizeSupabaseEndpoint(projectUrl) + "?id=eq." + id)
                .applyUser(apiKey, accessToken)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .header("User-Agent", "OwnLog/1.0")
                .patch(body.toRequestBody(JSON))
                .build()
        } catch (error: IllegalArgumentException) {
            return UploadOutcome.Failure(null, "Supabase URL 无法解析", retryable = false, config = true)
        }
        return execute(request)
    }

    fun signInWithPassword(projectUrl: String, apiKey: String, email: String, password: String, nowMillis: Long): AuthCall {
        val body = """{"email":${jsonString(email.trim())},"password":${jsonString(password)}}"""
        return authPost(projectUrl, apiKey, "/auth/v1/token?grant_type=password", body, nowMillis, passwordFailure = true)
    }

    fun refreshSession(projectUrl: String, apiKey: String, refreshToken: String, nowMillis: Long): AuthCall {
        val body = """{"refresh_token":${jsonString(refreshToken.trim())}}"""
        return authPost(projectUrl, apiKey, "/auth/v1/token?grant_type=refresh_token", body, nowMillis, passwordFailure = false)
    }

    fun sendEmailCode(projectUrl: String, apiKey: String, email: String): UploadOutcome {
        val blocked = blockKey(projectUrl, apiKey)
        if (blocked != null) return blocked
        val body = """{"email":${jsonString(email.trim())},"create_user":false}"""
        val request = try {
            Request.Builder()
                .url(projectRoot(projectUrl) + "/auth/v1/otp")
                .applyApiKey(apiKey)
                .header("Content-Type", "application/json")
                .header("User-Agent", "OwnLog/1.0")
                .post(body.toRequestBody(JSON))
                .build()
        } catch (error: IllegalArgumentException) {
            return UploadOutcome.Failure(null, "Supabase URL 无法解析", retryable = false, config = true)
        }
        return execute(request)
    }

    fun verifyEmailCode(
        projectUrl: String,
        apiKey: String,
        email: String,
        code: String,
        nowMillis: Long,
    ): AuthCall {
        val body = """{"email":${jsonString(email.trim())},"token":${jsonString(code.trim())},"type":"email"}"""
        return authPost(projectUrl, apiKey, "/auth/v1/verify", body, nowMillis, passwordFailure = true)
    }

    private fun postJson(
        url: String,
        apiKey: String,
        accessToken: String,
        json: String,
        extraHeaders: List<Pair<String, String>>,
    ): UploadOutcome {
        val request = try {
            val builder = Request.Builder()
                .url(url)
                .applyUser(apiKey, accessToken)
                .header("Content-Type", "application/json")
                .header("User-Agent", "OwnLog/1.0")
            for ((name, value) in extraHeaders) builder.header(name, value)
            builder.post(json.toRequestBody(JSON)).build()
        } catch (error: IllegalArgumentException) {
            return UploadOutcome.Failure(null, "Supabase URL 无法解析", retryable = false, config = true)
        }
        return execute(request)
    }

    private fun authPost(
        projectUrl: String,
        apiKey: String,
        path: String,
        body: String,
        nowMillis: Long,
        passwordFailure: Boolean,
    ): AuthCall {
        val blocked = blockKey(projectUrl, apiKey)
        if (blocked != null) {
            return AuthCall.Failure(blocked.message, invalid = true, retryable = false)
        }
        val request = try {
            Request.Builder()
                .url(projectRoot(projectUrl) + path)
                .applyApiKey(apiKey)
                .header("Content-Type", "application/json")
                .header("User-Agent", "OwnLog/1.0")
                .post(body.toRequestBody(JSON))
                .build()
        } catch (error: IllegalArgumentException) {
            return AuthCall.Failure("Supabase URL 无法解析", invalid = true, retryable = false)
        }
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val parsed = parseAuthResponse(text, nowMillis)
                    if (parsed == null) {
                        AuthCall.Failure("登录响应里没有会话", invalid = true, retryable = false)
                    } else {
                        AuthCall.Success(parsed)
                    }
                } else {
                    val invalid = response.code == 400 || response.code == 401 || response.code == 403 || response.code == 422
                    val retryable = response.code == 408 || response.code == 429 || response.code >= 500
                    val message = if (passwordFailure && invalid) {
                        "邮箱或密码不对。请确认这个账号是在 Supabase Dashboard → Authentication → Users 里建的。"
                    } else {
                        "HTTP ${response.code} ${text.take(180)}".trim()
                    }
                    AuthCall.Failure(message, invalid = invalid, retryable = retryable)
                }
            }
        } catch (error: Exception) {
            AuthCall.Failure(error.message ?: error.javaClass.simpleName, invalid = false, retryable = true)
        }
    }

    private fun refuse(projectUrl: String, apiKey: String, accessToken: String): UploadOutcome? {
        val blocked = blockKey(projectUrl, apiKey)
        if (blocked != null) return blocked
        if (accessToken.isBlank()) {
            return UploadOutcome.Failure(null, AUTH_SIGNED_OUT, retryable = false, config = true)
        }
        return null
    }

    private fun blockKey(projectUrl: String, apiKey: String): UploadOutcome.Failure? {
        if (projectUrl.isBlank() || apiKey.isBlank()) {
            return UploadOutcome.Failure(null, "还没有填写 Supabase URL 和 API key", retryable = false, config = true)
        }
        if (isSupabaseSecretKey(apiKey)) {
            return UploadOutcome.Failure(null, SUPABASE_SECRET_KEY_ERROR, retryable = false, config = true)
        }
        return null
    }

    private fun Request.Builder.applyUser(apiKey: String, accessToken: String): Request.Builder {
        header("apikey", apiKey.trim())
        header("Authorization", "Bearer ${accessToken.trim()}")
        return this
    }

    private fun Request.Builder.applyApiKey(apiKey: String): Request.Builder {
        val trimmed = apiKey.trim()
        header("apikey", trimmed)
        supabaseAuthorizationHeader(trimmed)?.let { header("Authorization", it) }
        return this
    }

    private fun execute(request: Request): UploadOutcome {
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful || response.code == 409) {
                    UploadOutcome.Success
                } else {
                    val body = response.body?.string().orEmpty().take(300)
                    val config = response.code == 401 || response.code == 403 || response.code == 404
                    val retryable = response.code == 408 || response.code == 429 || response.code >= 500
                    UploadOutcome.Failure(response.code, "HTTP ${response.code} $body".trim(), retryable, config)
                }
            }
        } catch (error: Exception) {
            UploadOutcome.Failure(null, error.message ?: error.javaClass.simpleName, retryable = true, config = false)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
