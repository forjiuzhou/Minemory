package dev.owner.ownlog.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.core.content.ContextCompat
import dev.owner.ownlog.service.ScreenOcrService

internal fun screenCaptureIntent(context: Context): Intent {
    val manager = context.getSystemService(MediaProjectionManager::class.java)
    return manager.createScreenCaptureIntent()
}

internal fun startScreenCapture(context: Context, resultCode: Int, data: Intent) {
    val intent = Intent(context, ScreenOcrService::class.java).apply {
        putExtra(ScreenOcrService.EXTRA_CODE, resultCode)
        putExtra(ScreenOcrService.EXTRA_DATA, data)
    }
    ContextCompat.startForegroundService(context, intent)
}
