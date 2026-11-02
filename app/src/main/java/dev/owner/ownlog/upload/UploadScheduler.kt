package dev.owner.ownlog.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object UploadScheduler {
    private const val PERIODIC = "ownlog-upload-periodic"
    private const val SOON = "ownlog-upload-soon"
    private const val NOW = "ownlog-upload-now"

    fun schedulePeriodic(context: Context, wifiOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun nudge(context: Context, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(wifiOnly))
            .setInitialDelay(20, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request)
    }

    fun nudgeNow(context: Context, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(wifiOnly))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
    }

    private fun constraints(wifiOnly: Boolean): Constraints {
        val type = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        return Constraints.Builder().setRequiredNetworkType(type).build()
    }
}
