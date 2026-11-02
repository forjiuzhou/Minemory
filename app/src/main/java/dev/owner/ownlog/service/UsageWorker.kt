package dev.owner.ownlog.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.ui.PermissionChecks
import java.util.concurrent.TimeUnit

class UsageWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? OwnLogApp ?: return Result.success()
        if (!app.settings.get().enableUsage) return Result.success()
        if (!PermissionChecks.usageGranted(applicationContext)) return Result.success()
        return try {
            UsageCollector.collect(applicationContext, app)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object UsageScheduler {
    private const val PERIODIC = "ownlog-usage"
    private const val SOON = "ownlog-usage-soon"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UsageWorker>(15, TimeUnit.MINUTES)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
        kick(context)
    }

    fun kick(context: Context) {
        val soon = OneTimeWorkRequestBuilder<UsageWorker>()
            .setInitialDelay(10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, soon)
    }
}
