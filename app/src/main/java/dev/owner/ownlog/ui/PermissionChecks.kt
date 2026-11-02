package dev.owner.ownlog.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.owner.ownlog.service.OwnAccessibilityService
import dev.owner.ownlog.service.OwnNotificationListenerService

internal data class PermissionSnapshot(
    val accessibility: Boolean,
    val notifications: Boolean,
    val listener: Boolean,
    val location: Boolean,
    val backgroundLocation: Boolean,
    val battery: Boolean,
    val usage: Boolean,
)

internal object PermissionChecks {
    fun read(context: Context): PermissionSnapshot {
        return PermissionSnapshot(
            accessibility = accessibilityEnabled(context),
            notifications = notificationsGranted(context),
            listener = listenerEnabled(context),
            location = locationGranted(context),
            backgroundLocation = backgroundGranted(context),
            battery = batteryIgnored(context),
            usage = usageGranted(context),
        )
    }

    fun usageGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun accessibilityEnabled(context: Context): Boolean {
        val component = ComponentName(context, OwnAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        return enabled.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
    }

    fun listenerEnabled(context: Context): Boolean {
        val component = ComponentName(context, OwnNotificationListenerService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        val full = component.flattenToString()
        val short = component.flattenToShortString()
        return enabled.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
    }

    fun notificationsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun locationGranted(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun backgroundGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 29) return locationGranted(context)
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun batteryIgnored(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }
}
