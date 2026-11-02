package dev.owner.ownlog.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.ui.PermissionChecks

internal fun syncPassiveCollectors(context: Context) {
    val app = context.applicationContext as? OwnLogApp ?: return
    val settings = app.settings.get()
    if (settings.enableLocation && PermissionChecks.locationGranted(context)) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LocationService::class.java).putExtra(LocationService.EXTRA_ENABLE, true),
                )
            } catch (_: Exception) {
            }
        }
    }
    if (!settings.deepMode || !settings.enableOcr) {
        context.stopService(Intent(context, ScreenOcrService::class.java))
    }
}
