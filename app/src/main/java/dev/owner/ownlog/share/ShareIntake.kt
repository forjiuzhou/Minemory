package dev.owner.ownlog.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
internal data class IncomingShare(
    val subject: String?,
    val text: String?,
    val mime: String?,
    val streams: List<Uri>,
    val sourcePackage: String?,
) {
}

internal fun Activity.readIncomingShare(): IncomingShare {
    val intent = intent
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.ifBlank { null }
    val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()?.trim()?.ifBlank { null }
    return IncomingShare(
        subject = subject,
        text = text,
        mime = intent.type,
        streams = intent.streamUris(),
        sourcePackage = shareSourcePackage(),
    )
}

private fun Activity.shareSourcePackage(): String? {
    val referrerHost = referrer?.takeIf { it.scheme == "android-app" }?.host
    if (!referrerHost.isNullOrBlank()) return referrerHost
    val extra = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_REFERRER, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_REFERRER)
    }
    val extraHost = extra?.takeIf { it.scheme == "android-app" }?.host
    if (!extraHost.isNullOrBlank()) return extraHost
    val named = intent.getStringExtra(Intent.EXTRA_REFERRER_NAME)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    if (named?.scheme == "android-app" && !named.host.isNullOrBlank()) return named.host
    return callingPackage
}

private fun Intent.streamUris(): List<Uri> {
    val found = LinkedHashSet<Uri>()
    fun add(uri: Uri?) {
        if (uri != null) found += uri
    }
    if (Build.VERSION.SDK_INT >= 33) {
        add(getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.forEach(::add)
    } else {
        @Suppress("DEPRECATION")
        add(getParcelableExtra(Intent.EXTRA_STREAM))
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach(::add)
    }
    val clip = clipData
    if (clip != null) {
        for (index in 0 until clip.itemCount) {
            add(clip.getItemAt(index)?.uri)
        }
    }
    return found.toList()
}
