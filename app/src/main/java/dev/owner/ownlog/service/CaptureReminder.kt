package dev.owner.ownlog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.owner.ownlog.MainActivity
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.R

internal object CaptureReminder {
    const val CHANNEL_ID = "ownlog-capture-grant"
    const val NOTIFICATION_ID = 44

    fun sync(context: Context) {
        val app = context.applicationContext as? OwnLogApp
        val settings = app?.settings?.get()
        val wanted = settings?.deepMode == true &&
            settings.enableOcr &&
            settings.onboardingDone &&
            !CaptureBridge.projectionActive.value
        if (wanted) show(context) else hide(context)
    }

    private fun show(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            7,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_GRANT_CAPTURE, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "重启后屏幕采集会失效。点「重新授权」才能继续截屏识别。"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ownlog)
            .setContentTitle("请重新授权截屏")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .addAction(R.drawable.ic_stat_ownlog, "重新授权", open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun hide(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "截屏授权",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "重启后提醒重新允许屏幕采集。"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
