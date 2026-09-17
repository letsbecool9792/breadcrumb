package com.lbc.breadcrumb.sync

import android.util.Log
import com.lbc.breadcrumb.data.MemoryDao
import com.lbc.breadcrumb.data.SyncState
import com.lbc.breadcrumb.net.MemoryUploadApi
import com.lbc.breadcrumb.net.UploadResult

/** How a pass over the queue ended. */
sealed interface UploadOutcome {
    /** Nothing left to send. */
    data class Done(val sent: Int) : UploadOutcome

    /** The server could not take them now; the rest are still queued. */
    data class RetryLater(val sent: Int, val reason: String) : UploadOutcome
}

/**
 * Sends saved memories to the server, oldest first.
 *
 * Nothing here is on the save path (architecture rule 2): capture writes to
 * Room and returns, and this runs later, from a worker, whenever there is a
 * network. Every send is idempotent on the memory's own id, so a retry after a
 * half-finished upload replaces rather than duplicates -- which is what makes
 * it safe to retry at all.
 */
class MemoryUploader(
    private val dao: MemoryDao,
    private val api: MemoryUploadApi,
    /** Memories per request. One model call covers the batch, so this is quota. */
    private val batchSize: Int = 10,
    /** How long a freshly saved image may wait for OCR before it is sent anyway. */
    private val ocrGraceMillis: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun uploadPending(): UploadOutcome {
        // A request in flight when the process died left its row UPLOADING.
        // Uploads are unique work, so nothing else is sending right now.
        dao.resetStaleUploads()

        var sent = 0
        while (true) {
            val batch = dao.pendingUploads(ocrDeadline = clock() - ocrGraceMillis, limit = batchSize)
            if (batch.isEmpty()) return UploadOutcome.Done(sent)

            // Claimed by nobody else, and still PENDING: anything that changed
            // under us -- deleted, or re-queued -- is left for next time.
            val claimed = batch.filter { dao.markUploading(it.id) > 0 }
            if (claimed.isEmpty()) continue

            val results = api.upload(claimed)
            var waiting: String? = null

            for (memory in claimed) {
                when (val result = results[memory.id] ?: UploadResult.Unavailable("no answer")) {
                    is UploadResult.Stored -> {
                        dao.markSynced(memory.id, result.remoteId)
                        sent += 1
                    }

                    is UploadResult.Rejected -> {
                        // Sending the same bytes again gets the same refusal.
                        Log.w(TAG, "server refused ${memory.id}: ${result.reason}")
                        dao.markUploadEnded(memory.id, SyncState.FAILED)
                    }

                    is UploadResult.Unavailable -> {
                        dao.markUploadEnded(memory.id, SyncState.PENDING)
                        waiting = result.reason
                    }
                }
            }

            // Something is queued again -- the server offline, or a model call
            // to retry. The next batch would fare the same, and WorkManager's
            // backoff is a better place to wait than a loop here.
            if (waiting != null) return UploadOutcome.RetryLater(sent, waiting)
        }
    }

    private companion object {
        const val TAG = "MemoryUploader"
    }
}
