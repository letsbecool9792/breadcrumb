package com.lbc.breadcrumb.capture

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryDao
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.OriginalStore
import java.io.File

/** The memories actually written -- what the capture sheet shows, and what undo removes. */
data class CaptureResult(val memories: List<Memory>, val attempted: Int) {
    val saved: Int get() = memories.size
}

/**
 * Copies shared files in and writes their memories. Everything here is local
 * disk and local database -- no network (architecture rule 2).
 */
class MediaCapture(
    private val context: Context,
    private val dao: MemoryDao,
    private val store: OriginalStore,
) {

    /**
     * @param note the person's own words, given to every file saved -- from
     *   the app's own sheet, where they wrote one line for what they picked.
     */
    suspend fun save(
        uris: List<Uri>,
        intentType: String?,
        sharedText: String?,
        subject: String?,
        source: Provenance?,
        now: Long = System.currentTimeMillis(),
        note: String? = null,
    ): CaptureResult {
        val accepted = uris.filter { MediaShareParser.isAcceptableScheme(it.scheme) }
        val saved = mutableListOf<Memory>()

        for (uri in accepted) {
            val incoming = describe(uri, intentType)
            val draft = MediaShareParser.plan(
                item = incoming,
                sharedText = sharedText,
                subject = subject,
                isOnlyItem = accepted.size == 1,
            ) ?: continue

            val file = try {
                store.copyIn(uri, draft.id, draft.extension)
            } catch (e: Exception) {
                // One unreadable item must not sink the rest of a multi-share.
                Log.w(TAG, "could not copy $uri", e)
                continue
            }

            val createdAt = draft.contentCreatedAt ?: exifDate(file, draft.type)
            val memory = draft.copy(contentCreatedAt = createdAt).toMemory(
                localUri = store.uriFor(file),
                sourceApp = source?.packageName,
                sourceAppLabel = source?.label,
                now = now,
            ).copy(note = note)

            try {
                dao.upsert(memory)
                saved += memory
            } catch (e: Exception) {
                Log.w(TAG, "could not record $uri", e)
                file.delete()
            }
        }

        return CaptureResult(memories = saved, attempted = uris.size)
    }

    /**
     * Reads what the sending provider will tell us. Only possible now: the
     * read grant ends with the share, so a creation date not captured here is
     * lost for good -- which is why it is gathered at 1.4 and not later.
     */
    private fun describe(uri: Uri, intentType: String?): IncomingMedia {
        val resolver = context.contentResolver
        // The provider knows the exact type; the intent's may be "image/*".
        val mime = runCatching { resolver.getType(uri) }.getOrNull() ?: intentType

        var displayName: String? = null
        var createdAt: Long? = null

        // Null projection rather than naming columns: providers that are not
        // MediaStore often throw on columns they do not know, while returning
        // whatever they do support for a null projection.
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use

                c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { displayName = c.getString(it) }

                val taken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    .takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { c.getLong(it) }
                    ?.takeIf { it > 0 }

                // DATE_MODIFIED is in seconds, DATE_TAKEN in milliseconds.
                val modified = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    .takeIf { it >= 0 && !c.isNull(it) }
                    ?.let { c.getLong(it) * 1000 }
                    ?.takeIf { it > 0 }

                createdAt = taken ?: modified
            }
        }.onFailure { Log.d(TAG, "provider query failed for $uri", it) }

        return IncomingMedia(mime = mime, displayName = displayName, createdAt = createdAt)
    }

    /** Fallback for providers that report no dates, e.g. WhatsApp's. */
    private fun exifDate(file: File, type: MemoryType): Long? {
        if (type == MemoryType.PDF) return null
        return runCatching {
            ExifDates.parse(ExifInterface(file).getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        }.getOrNull()
    }

    private companion object {
        const val TAG = "MediaCapture"
    }
}
