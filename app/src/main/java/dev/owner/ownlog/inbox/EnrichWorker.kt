package dev.owner.ownlog.inbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.domain.parsePageMeta
import dev.owner.ownlog.service.OcrRunner
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal object ImageText {
    suspend fun read(file: File): String? {
        val bitmap = decode(file) ?: return null
        val lines = OcrRunner().read(bitmap)?.lines ?: return null
        return lines.joinToString("\n").trim().ifBlank { null }
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1600) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

internal object PageFetcher {
    private const val MAX_BYTES = 256L * 1024
    private val client = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun fetch(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != "https" && parsed.scheme != "http") return null
        val request = Request.Builder()
            .url(parsed)
            .header("User-Agent", "OwnLog/1.0")
            .header("Accept", "text/html,application/xhtml+xml")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val type = response.body?.contentType()?.toString().orEmpty().lowercase()
                if (type.isNotBlank() && !type.contains("html") && !type.contains("text") && !type.contains("xml")) {
                    return null
                }
                val source = response.body?.source() ?: return null
                source.request(MAX_BYTES)
                val size = minOf(source.buffer.size, MAX_BYTES)
                if (size <= 0L) null else source.buffer.readUtf8(size)
            }
        } catch (_: Exception) {
            null
        }
    }
}

class EnrichWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? OwnLogApp ?: return Result.success()
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val url = inputData.getString(KEY_URL)
        val path = inputData.getString(KEY_PATH)
        if (!url.isNullOrBlank()) {
            val html = PageFetcher.fetch(url)
            if (html == null) {
                if (runAttemptCount < 1) return Result.retry()
            } else {
                app.payloads.applyPageMeta(id, parsePageMeta(html))
            }
        }
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists()) {
                val text = try {
                    ImageText.read(file)
                } catch (_: Exception) {
                    null
                }
                if (!text.isNullOrBlank()) {
                    val title = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(80)
                    app.payloads.replaceContent(id, text.take(8_000), title)
                }
            }
        }
        return Result.success()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_URL = "url"
        const val KEY_PATH = "path"
    }
}

object EnrichScheduler {
    fun enqueue(context: Context, id: String, url: String?, localPath: String?) {
        if (url.isNullOrBlank() && localPath.isNullOrBlank()) return
        val data = workDataOf(
            EnrichWorker.KEY_ID to id,
            EnrichWorker.KEY_URL to url,
            EnrichWorker.KEY_PATH to localPath,
        )
        val builder = OneTimeWorkRequestBuilder<EnrichWorker>()
            .setInputData(data)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        if (!url.isNullOrBlank()) {
            builder.setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
        }
        WorkManager.getInstance(context).enqueue(builder.build())
    }
}
