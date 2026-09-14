package com.lbc.breadcrumb.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * One saved thing. The row is written the instant the user shares or taps the
 * tile; everything downstream (OCR, Gemini enrichment, upload) fills in nullable
 * columns later. Saving must never wait on any of it.
 */
@Entity(
    tableName = "memories",
    indices = [
        Index("capturedAt"),   // list ordering
        Index("syncState"),    // upload queue lookup
        Index("type"),         // type filter during retrieval
    ],
)
data class Memory(
    /**
     * Generated on-device so an offline save has a stable identity before the
     * server has ever seen it. Re-sending is then idempotent.
     */
    @PrimaryKey val id: String = UUID.randomUUID().toString(),

    /**
     * The primary kind -- what the memory *is*, and so what its tile looks
     * like. A file decides it (IMAGE, PDF); otherwise LINK when the text
     * carries a URL; otherwise TEXT.
     */
    val type: MemoryType,

    /**
     * True when the shared text carries a URL, whatever the primary type. This
     * is what lets a photo sent with a link wear both an IMAGE and a LINK chip,
     * and what a "that link" search must filter on -- `type = LINK` alone
     * misses every captioned photo and PDF.
     *
     * Set from shared text only, never from OCR: a screenshot with a URL bar in
     * it is not a link someone sent.
     */
    @ColumnInfo(defaultValue = "0")
    val hasLink: Boolean = false,

    /** When the user saved it. */
    val capturedAt: Long = System.currentTimeMillis(),

    /**
     * When the underlying content was created, when that is knowable -- a
     * screenshot's own file date, say. This is what answers "from April";
     * capturedAt often is not the same thing.
     */
    val contentCreatedAt: Long? = null,

    /** Package name of the app that shared it, via Activity.getReferrer(). */
    val sourceApp: String? = null,

    /** Shared text, URL, or clipboard content. */
    val rawText: String? = null,

    /** App-private copy of the original file, for image/pdf/audio. */
    val localUri: String? = null,

    /** Filled by on-device ML Kit OCR (step 2.1). */
    val extractedText: String? = null,

    /**
     * Starts as whatever the sharing app offered as a subject (Chrome sends the
     * page title), so the list reads properly before anything has been
     * processed. The Gemini ingest pass (step 3.3) refines it.
     */
    val title: String? = null,
    val summary: String? = null,
    val entitiesJson: String? = null,

    /** Server-side id, once this row has synced. */
    val remoteId: String? = null,

    val syncState: SyncState = SyncState.PENDING,

    val updatedAt: Long = System.currentTimeMillis(),
) {
    /**
     * Everything worth matching against, newline-joined. Local search uses this
     * now; it is also what gets embedded server-side later.
     */
    val searchableText: String
        get() = listOfNotNull(title, summary, rawText, extractedText)
            .filter { it.isNotBlank() }
            .joinToString("\n")

    /** Primary type first, then LINK when a link rides along with something else. */
    val chips: List<MemoryType>
        get() = if (hasLink && type != MemoryType.LINK) listOf(type, MemoryType.LINK) else listOf(type)
}

enum class MemoryType {
    TEXT,
    LINK,
    IMAGE,
    PDF,

    /** Out of scope for V1, declared now so enabling it needs no migration. */
    AUDIO,
}

enum class SyncState {
    /** Saved locally, not yet sent. */
    PENDING,

    /** Handed to WorkManager, upload in flight. */
    UPLOADING,

    /** Processed server-side; embedding exists. */
    SYNCED,

    /** Upload or processing failed; eligible for retry. */
    FAILED,
}
