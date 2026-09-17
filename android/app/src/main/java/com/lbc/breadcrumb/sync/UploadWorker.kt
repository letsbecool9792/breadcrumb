package com.lbc.breadcrumb.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lbc.breadcrumb.BreadcrumbApp
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import java.util.concurrent.TimeUnit

/**
 * Runs the upload queue. WorkManager owns the waiting: it holds the job across
 * process death and reboots, starts it when there is a network, and backs off
 * when the server is not answering.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BreadcrumbApp
        val uploader = MemoryUploader(BreadcrumbDatabase.get(applicationContext).memoryDao(), app.server)

        return when (val outcome = uploader.uploadPending()) {
            is UploadOutcome.Done -> {
                if (outcome.sent > 0) Log.i(TAG, "uploaded ${outcome.sent}")
                Result.success()
            }

            is UploadOutcome.RetryLater -> {
                Log.i(TAG, "uploaded ${outcome.sent}, stopping: ${outcome.reason}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "UploadWorker"
        private const val WORK_NAME = "upload-memories"

        /**
         * Asks for a pass over the queue. Safe to call on every save: the work
         * is unique, and a pass already waiting will pick up whatever is
         * queued by the time it runs.
         */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
