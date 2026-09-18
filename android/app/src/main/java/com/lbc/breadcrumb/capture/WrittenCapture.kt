package com.lbc.breadcrumb.capture

import android.content.Context
import android.net.Uri
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryDao
import com.lbc.breadcrumb.data.OriginalStore

/**
 * Saves what was put into the app's own sheet (the "+"): a thought typed in,
 * or photos and PDFs picked, with a line about them.
 *
 * The same rules as a share, so a memory made here is no different from one
 * shared in. Text alone is the memory itself -- a note, or a link when it
 * holds a URL. With files, each file is its own memory, and the text is the
 * note on every one: the person wrote it about what they picked, not as a
 * caption someone sent. No source app -- it came from the person.
 *
 * Local disk and database only (architecture rule 2).
 */
class WrittenCapture(
    private val context: Context,
    private val dao: MemoryDao,
    private val store: OriginalStore,
) {

    /** @return the memories written; empty when there was nothing to keep, or every file failed to copy. */
    suspend fun save(text: String, files: List<Uri>, now: Long = System.currentTimeMillis()): List<Memory> {
        val words = text.trim()
        if (files.isEmpty()) {
            val memory = ShareParser.parse(words, now = now) ?: return emptyList()
            dao.upsert(memory)
            return listOf(memory)
        }
        return MediaCapture(context, dao, store).save(
            uris = files,
            intentType = null,
            sharedText = null,
            subject = null,
            source = null,
            now = now,
            note = words.ifEmpty { null },
        ).memories
    }
}
