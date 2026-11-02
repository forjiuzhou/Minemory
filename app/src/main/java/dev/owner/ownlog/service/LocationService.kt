package dev.owner.ownlog.service

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.owner.ownlog.OwnLogApp
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

class LocationService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private var callback: LocationCallback? = null
    private var updatesStarted = false

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        val app = application as? OwnLogApp
        val requested = intent?.getBooleanExtra(EXTRA_ENABLE, false) == true
        val enabled = requested || app?.settings?.get()?.enableLocation == true
        if (!enabled || !hasLocationPermission()) {
            shutdown()
            return START_NOT_STICKY
        }
        if (!updatesStarted && !startUpdates(app)) {
            shutdown()
            return START_NOT_STICKY
        }
        updatesStarted = true
        return START_STICKY
    }

    override fun onDestroy() {
        stopUpdates()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification = Notifier.ongoing(
            this,
            "自记正在记录位置",
            "移动大约 100 米会记一条，停留时大约每 8 分钟记一条。",
        )
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.LOCATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(Notifier.LOCATION_ID, notification)
        }
    }

    private fun startUpdates(app: OwnLogApp?): Boolean {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        if (availability != ConnectionResult.SUCCESS) {
            app?.appScope?.launch {
                app.settings.setCollectorError("这台设备没有可用的 Google Play 服务，定位没有启动。")
            }
            return false
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L)
            .setMinUpdateIntervalMillis(30_000L)
            .setMinUpdateDistanceMeters(0f)
            .setMaxUpdateDelayMillis(60_000L)
            .build()
        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val pipeline = (application as? OwnLogApp)?.pipeline ?: return
                pipeline.onLocation(
                    lat = location.latitude,
                    lng = location.longitude,
                    accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else 0.0,
                    timeMillis = if (location.time > 0) location.time else System.currentTimeMillis(),
                    provider = location.provider,
                )
            }
        }
        callback = locationCallback
        return try {
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(request, executor, locationCallback)
            true
        } catch (error: SecurityException) {
            Log.w(TAG, "location permission missing: ${error.message}")
            callback = null
            false
        }
    }

    private fun stopUpdates() {
        val current = callback ?: return
        callback = null
        try {
            LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(current)
        } catch (_: Exception) {
        }
    }

    private fun shutdown() {
        updatesStarted = false
        stopUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val EXTRA_ENABLE = "enable"
        private const val TAG = "OwnLog"
    }
}
