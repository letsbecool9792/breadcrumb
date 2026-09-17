package com.lbc.breadcrumb.ocr

import android.util.Log
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryDao
import com.lbc.breadcrumb.data.OriginalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate

/**
 * Reads the text out of saved images on the device (architecture rule 4), so a
 * screenshot is searchable by what it says without any backend.
 *
 * Nothing has to ask for it. The queue watches for image memories that have
 * not been read yet and works through them, newest first, whatever saved the
 * row -- a share, the clipboard tile, a later import. Capture never waits on it
 * (rule 2): "Saved" comes first, and the text follows a moment later.
 *
 * The same watch is the recovery path. A read cut short when the process dies
 * leaves its row unread, and the next start picks it up -- as it does images
 * saved before OCR existed.
 */
class OcrQueue(
    private val dao: MemoryDao,
    private val store: OriginalStore,
    private val reader: OcrReader,
    private val batchSize: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Called after text is written. Reading an image queues it to sync again,
     * since the server has not seen those words yet (step 3.5).
     */
    private val onRead: () -> Unit = {},
) {

    /**
     * Ids whose read threw, passed over until the process restarts. Retrying
     * at once would most likely fail the same way, and retrying on every later
     * change to the table would spin. Only [drain] touches it, and a drain
     * never overlaps another.
     */
    private val failed = mutableSetOf<String>()

    /** Runs for the life of the process. */
    suspend fun run() {
        dao.observeUnreadImageCount()
            // a burst of saves while a drain runs becomes one more drain, not many
            .conflate()
            .collect { unread -> if (unread > 0) drain() }
    }

    /** Reads every unread image, then returns. */
    suspend fun drain() {
        try {
            while (true) {
                val batch = dao.unreadImages(exclude = failed.toList(), limit = batchSize)
                if (batch.isEmpty()) return
                batch.forEach { read(it) }
            }
        } finally {
            reader.release()
        }
    }

    private suspend fun read(memory: Memory) {
        // No stored file -- a sample row, or an original gone from disk -- is
        // as final an answer as an image with no text in it.
        val file = store.fileFor(memory)
        val text = if (file == null) {
            ""
        } else {
            try {
                reader.read(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "could not read ${memory.id}", e)
                failed += memory.id
                return
            }
        }
        dao.setExtractedText(memory.id, text, clock())
        if (text.isNotEmpty()) onRead()
    }

    private companion object {
        const val TAG = "OcrQueue"
    }
}
