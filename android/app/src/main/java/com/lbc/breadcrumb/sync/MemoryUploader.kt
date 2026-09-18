package com.lbc.breadcrumb.sync

import android.util.Log
import com.lbc.breadcrumb.data.MemoryDao
import com.lbc.breadcrumb.data.OriginalStore
import com.lbc.breadcrumb.data.SyncState
import com.lbc.breadcrumb.net.MemoryUploadApi
import com.lbc.breadcrumb.net.OutgoingImage
import com.lbc.breadcrumb.net.UploadResult
import java.io.File

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
    private val store: OriginalStore,
    /** Memories per request. One model call covers the batch, so this is quota. */
    private val batchSize: Int = 10,
    /** Pictures per request -- fewer, since each costs about a thousand tokens. */
    private val imageBatchSize: Int = 4,
    /** Ids per delete or enrichment request. No model call is involved, so only size limits it. */
    private val syncBatchSize: Int = 50,
    /** Below this much text in an image, the picture itself is worth reading. */
    private val minTextForReading: Int = 80,
    /** How long a freshly saved image may wait for OCR before it is sent anyway. */
    private val ocrGraceMillis: Long = 10 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Injected so the queue's bookkeeping can be tested without decoding bitmaps. */
    private val prepareImage: (File) -> ByteArray? = { ImageForUpload.prepare(it) },
    /**
     * Memories not to send yet: those whose capture sheet is still open, since
     * a note written there would otherwise cost a second send. Read afresh
     * for every batch, so one released mid-run goes in the same run.
     */
    private val held: () -> Collection<String> = { emptySet() },
) {

    /**
     * Sends the pictures of images whose words OCR could not read (step 3.6),
     * after their memories are on the server.
     *
     * Kept to a few at a time and to the images that need it: a picture costs
     * roughly ten times the model tokens of the text beside it.
     */
    suspend fun uploadImages(): UploadOutcome {
        var sent = 0
        while (true) {
            val waiting = dao.imagesAwaitingRead(
                minChars = minTextForReading,
                ocrDeadline = clock() - ocrGraceMillis,
                limit = imageBatchSize,
            )
            if (waiting.isEmpty()) return UploadOutcome.Done(sent)

            val images = waiting.mapNotNull { memory ->
                val file = store.fileFor(memory)
                val bytes = file?.let { prepareImage(it) }
                if (bytes == null) {
                    // Gone, or not a picture we can read. Marking it sent stops
                    // us coming back to it every pass for nothing.
                    Log.w(TAG, "nothing to send for ${memory.id}")
                    dao.markImageSent(memory.id, clock())
                    null
                } else {
                    OutgoingImage(memory.id, ImageForUpload.MIME_TYPE, bytes)
                }
            }
            if (images.isEmpty()) continue

            val results = api.uploadImages(images)
            var waitingReason: String? = null

            for (image in images) {
                when (val result = results[image.memoryId] ?: UploadResult.Unavailable("no answer")) {
                    is UploadResult.Stored -> {
                        dao.markImageSent(image.memoryId, clock())
                        sent += 1
                    }

                    is UploadResult.Rejected -> {
                        // The server will not read it however often we ask.
                        Log.w(TAG, "server refused the picture for ${image.memoryId}: ${result.reason}")
                        dao.markImageSent(image.memoryId, clock())
                    }

                    is UploadResult.Unavailable -> waitingReason = result.reason
                }
            }

            if (waitingReason != null) return UploadOutcome.RetryLater(sent, waitingReason)
        }
    }

    suspend fun uploadPending(): UploadOutcome {
        // A request in flight when the process died left its row UPLOADING.
        // Uploads are unique work, so nothing else is sending right now.
        dao.resetStaleUploads()

        var sent = 0
        while (true) {
            val batch = dao.pendingUploads(
                ocrDeadline = clock() - ocrGraceMillis,
                exclude = held().toList(),
                limit = batchSize,
            )
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

    /**
     * Sends deletes made on the phone (step 4.6). The server's delete is
     * idempotent, so a batch that fails is simply sent again next time.
     */
    suspend fun sendDeletes(): UploadOutcome {
        var sent = 0
        while (true) {
            val ids = dao.pendingDeletes(limit = syncBatchSize)
            if (ids.isEmpty()) return UploadOutcome.Done(sent)
            if (!api.delete(ids)) return UploadOutcome.RetryLater(sent, "the server did not take ${ids.size} deletes")
            dao.forgetDeletes(ids)
            sent += ids.size
        }
    }

    /**
     * Copies back what the model made of memories the server holds (step
     * 4.6): the summary, the kind, what it saw in a picture. Every memory
     * asked about is marked as copied, answer or not -- one the server holds
     * without enrichment is asked about again only after it is next sent.
     */
    suspend fun fetchEnrichment(): UploadOutcome {
        var copied = 0
        while (true) {
            val ids = dao.awaitingEnrichment(limit = syncBatchSize)
            if (ids.isEmpty()) return UploadOutcome.Done(copied)

            val answers = api.enrichment(ids)
                ?: return UploadOutcome.RetryLater(copied, "could not ask for ${ids.size} enrichments")
            for (id in ids) {
                val answer = answers[id]
                dao.setEnrichment(id, answer?.summary, answer?.kind, answer?.readText, clock())
                if (answer?.summary != null) copied += 1
            }
        }
    }

    private companion object {
        const val TAG = "MemoryUploader"
    }
}
