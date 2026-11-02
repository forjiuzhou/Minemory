package dev.owner.ownlog.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.data.UploadRunResult
import java.util.concurrent.TimeUnit

class FileUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? OwnLogApp ?: return Result.success()
        return try {
            when (app.fileUploader.runOnce()) {
                UploadRunResult.Idle, UploadRunResult.Done -> Result.success()
                is UploadRunResult.Retry -> Result.retry()
                is UploadRunResult.Stop -> Result.failure()
            }
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object FileUploadScheduler {
    private const val PERIODIC = "ownlog-file-periodic"
    private const val SOON = "ownlog-file-soon"
    private const val NOW = "ownlog-file-now"

    fun schedulePeriodic(context: Context, wifiOnly: Boolean) {
        val request = PeriodicWorkRequestBuilder<FileUploadWorker>(15, TimeUnit.MINUTES)
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
        val request = OneTimeWorkRequestBuilder<FileUploadWorker>()
            .setConstraints(constraints(wifiOnly))
            .setInitialDelay(45, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.KEEP, request)
    }

    fun nudgeNow(context: Context, wifiOnly: Boolean) {
        val request = OneTimeWorkRequestBuilder<FileUploadWorker>()
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
