package dev.owner.ownlog.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.service.UsageScheduler
import dev.owner.ownlog.upload.FileUploadScheduler
import dev.owner.ownlog.upload.UploadScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as? OwnLogApp
        val wifi = app?.settings?.get()?.uploadWifiOnly == true
        UploadScheduler.schedulePeriodic(context, wifi)
        FileUploadScheduler.schedulePeriodic(context, wifi)
        UsageScheduler.schedule(context)
        CaptureReminder.sync(context)
        if (app == null) return
        if (!app.settings.get().enableLocation) return
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocationService::class.java).putExtra(LocationService.EXTRA_ENABLE, true),
            )
        } catch (error: Exception) {
            Log.w("OwnLog", "boot location start failed: ${error.message}")
        }
    }
}
