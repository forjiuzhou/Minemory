package dev.owner.ownlog.service

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.domain.NotificationSnap

class OwnNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val info = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.trim()
        val summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim()
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        val messages = messageLines(extras)
        val parts = (listOfNotNull(text, big?.takeIf { it != text }, info, summary) + messages)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (title.isNullOrBlank() && parts.isEmpty()) return
        val pipeline = (application as? OwnLogApp)?.pipeline ?: return
        pipeline.onNotification(
            NotificationSnap(
                key = "${sbn.packageName}:${sbn.id}:${sbn.tag.orEmpty()}",
                packageName = sbn.packageName,
                title = title?.take(500),
                content = parts.joinToString("\n").take(4_000).ifBlank { null },
                subText = sub?.take(300),
                channelId = notification.channelId,
                category = notification.category,
                notificationId = sbn.id,
                ongoing = sbn.isOngoing,
                postedAt = if (sbn.postTime > 0) sbn.postTime else System.currentTimeMillis(),
            ),
        )
    }

    private fun messageLines(extras: Bundle): List<String> {
        val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
        val lines = ArrayList<String>(raw.size)
        for (item in raw.takeLast(5)) {
            val bundle = item as? Bundle ?: continue
            val text = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (text.isEmpty()) continue
            val sender = bundle.getCharSequence("sender")?.toString()?.trim().orEmpty()
            lines += if (sender.isEmpty()) text else "$sender: $text"
        }
        return lines
    }
}
