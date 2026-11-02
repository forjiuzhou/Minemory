package dev.owner.ownlog.upload

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.owner.ownlog.OwnLogApp
import dev.owner.ownlog.data.UploadRunResult

class UploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? OwnLogApp ?: return Result.failure()
        return try {
            when (val outcome = app.uploader.runOnce()) {
                UploadRunResult.Idle, UploadRunResult.Done -> Result.success()
                is UploadRunResult.Retry -> Result.retry()
                is UploadRunResult.Stop -> Result.failure()
            }
        } catch (error: Exception) {
            Log.w(TAG, "upload failed: ${error.message}")
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "OwnLog"
    }
}
