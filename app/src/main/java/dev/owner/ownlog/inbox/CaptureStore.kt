package dev.owner.ownlog.inbox

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.domain.extensionFor
import dev.owner.ownlog.domain.inboxObjectPath
import dev.owner.ownlog.domain.mapNote
import dev.owner.ownlog.domain.mapPhoto
import dev.owner.ownlog.domain.mapShare
import dev.owner.ownlog.domain.mapVoice
import dev.owner.ownlog.service.AppLabels
import dev.owner.ownlog.upload.FileUploadScheduler
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

internal class CaptureStore(private val app: OwnLogApp) {
    suspend fun addNote(text: String, note: String?): Boolean {
        val body = text.trim()
        if (body.isBlank()) return false
        val mapped = mapNote(body, note)
        val deviceId = app.settings.ensureDeviceId()
        app.repository.record(
            type = mapped.type,
            occurredAt = System.currentTimeMillis(),
            packageName = app.packageName,
            appLabel = "自记",
            title = mapped.title,
            content = mapped.content,
            extrasJson = mapped.extrasJson,
            deviceId = deviceId,
        )
        return true
    }

    suspend fun addPhoto(file: File, mime: String, note: String?): String {
        return storeFile(
            file = file,
            mime = mime,
            note = note,
            image = true,
            voiceTranscript = null,
            share = false,
            subject = null,
            sharedText = null,
            explicitPackage = null,
        )
    }

    suspend fun addVoice(file: File, transcript: String?, note: String?): String {
        return storeFile(
            file = file,
            mime = "audio/mp4",
            note = note,
            image = false,
            voiceTranscript = transcript,
            share = false,
            subject = null,
            sharedText = null,
            explicitPackage = null,
        )
    }

    suspend fun addShare(
        subject: String?,
        text: String?,
        note: String?,
        explicitPackage: String?,
        file: File?,
        mime: String?,
        image: Boolean,
    ): String {
        if (!app.settings.get().enableShare) return ""
        if (file == null) {
            val mapped = mapShare(subject, text, note, explicitPackage, image = false)
            if (mapped.content.isNullOrBlank()) return ""
            val deviceId = app.settings.ensureDeviceId()
            val source = dev.owner.ownlog.domain.inferSourcePackage(
                explicitPackage,
                dev.owner.ownlog.domain.extractUrl(mapped.content),
            )
            val id = app.repository.record(
                type = mapped.type,
                occurredAt = System.currentTimeMillis(),
                packageName = source,
                appLabel = labelFor(source),
                title = mapped.title,
                content = mapped.content,
                extrasJson = mapped.extrasJson,
                deviceId = deviceId,
            )
            val url = dev.owner.ownlog.domain.extractUrl(mapped.content)
            if (!url.isNullOrBlank()) EnrichScheduler.enqueue(app, id, url, null)
            return id
        }
        return storeFile(
            file = file,
            mime = mime ?: "application/octet-stream",
            note = note,
            image = image,
            voiceTranscript = null,
            share = true,
            subject = subject,
            sharedText = text,
            explicitPackage = explicitPackage,
        )
    }

    private suspend fun storeFile(
        file: File,
        mime: String,
        note: String?,
        image: Boolean,
        voiceTranscript: String?,
        share: Boolean,
        subject: String?,
        sharedText: String?,
        explicitPackage: String?,
    ): String {
        val deviceId = app.settings.ensureDeviceId()
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val ext = extensionFor(mime, file.name)
        val owner = app.authStore.userId()?.lowercase() ?: "pending"
        val objectPath = inboxObjectPath(owner, now, id, ext)
        val bytes = file.length()
        val event = when {
            share -> mapShare(subject, sharedText, note, explicitPackage, image, objectPath, mime, bytes)
            image -> mapPhoto(null, note, objectPath, mime, bytes)
            else -> mapVoice(voiceTranscript, note, objectPath, mime, bytes)
        }
        val source = if (share) {
            dev.owner.ownlog.domain.inferSourcePackage(explicitPackage, dev.owner.ownlog.domain.extractUrl(event.content))
        } else {
            null
        }
        app.repository.record(
            type = event.type,
            occurredAt = now,
            packageName = if (share) source else app.packageName,
            appLabel = if (share) labelFor(source) else "自记",
            title = event.title,
            content = event.content,
            extrasJson = event.extrasJson,
            deviceId = deviceId,
            id = id,
        )
        app.files.enqueue(id, file.absolutePath, objectPath, mime, bytes)
        if (image) {
            app.appScope.launch {
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
        val url = if (share) dev.owner.ownlog.domain.extractUrl(event.content) else null
        if (image || !url.isNullOrBlank()) {
            EnrichScheduler.enqueue(app, id, url, if (image) file.absolutePath else null)
        }
        return id
    }

    private fun labelFor(packageName: String?): String {
        if (packageName.isNullOrBlank()) return "自记"
        return AppLabels.label(app, packageName)
    }
}

internal object InboxFiles {
    fun copy(context: Context, uri: Uri, mime: String?): File? {
        val name = displayName(context, uri)
        val ext = extensionFor(mime, name)
        val dir = File(context.filesDir, "inbox").apply { mkdirs() }
        val dest = File(dir, "${UUID.randomUUID()}.$ext")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return null
            dest.takeIf { it.exists() && it.length() > 0L }
        } catch (_: Exception) {
            dest.delete()
            null
        }
    }

    fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        } catch (_: Exception) {
            null
        }
    }
}

internal fun rescheduleUploads(context: Context, app: OwnLogApp) {
    val wifi = app.settings.get().uploadWifiOnly
    dev.owner.ownlog.upload.UploadScheduler.schedulePeriodic(context, wifi)
    FileUploadScheduler.schedulePeriodic(context, wifi)
}
