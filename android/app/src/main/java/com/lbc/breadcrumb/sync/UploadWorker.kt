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
import com.lbc.breadcrumb.data.OriginalStore
import java.util.concurrent.TimeUnit

/**
 * Runs the upload queue. WorkManager owns the waiting: it holds the job across
 * process death and reboots, starts it when there is a network, and backs off
 * when the server is not answering.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as BreadcrumbApp
        val uploader = MemoryUploader(
            dao = BreadcrumbDatabase.get(applicationContext).memoryDao(),
            api = app.server,
            store = OriginalStore(applicationContext),
        )

        // Deletes first: nothing else needs to wait on them, and a memory
        // deleted and then restored has already been re-queued as PENDING.
        val deletes = uploader.sendDeletes()
        if (deletes is UploadOutcome.RetryLater) {
            Log.i(TAG, "deleted ${deletes.sent}, stopping: ${deletes.reason}")
            return Result.retry()
        }

        // Memories next: a picture is only read for a memory the server holds.
        val memories = uploader.uploadPending()
        if (memories is UploadOutcome.RetryLater) {
            Log.i(TAG, "uploaded ${memories.sent}, stopping: ${memories.reason}")
            return Result.retry()
        }

        val images = uploader.uploadImages()
        if (images is UploadOutcome.RetryLater) {
            Log.i(TAG, "read ${images.sent} pictures, stopping: ${images.reason}")
            return Result.retry()
        }

        // Last, what the model made of all of it, copied back to the phone.
        val enriched = uploader.fetchEnrichment()
        if (enriched is UploadOutcome.RetryLater) {
            Log.i(TAG, "copied ${enriched.sent} enrichments, stopping: ${enriched.reason}")
            return Result.retry()
        }

        val counts = listOf(deletes, memories, images, enriched).map { (it as UploadOutcome.Done).sent }
        if (counts.any { it > 0 }) {
            Log.i(TAG, "deleted ${counts[0]}, uploaded ${counts[1]}, read ${counts[2]} pictures, copied ${counts[3]} enrichments")
        }
        return Result.success()
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
