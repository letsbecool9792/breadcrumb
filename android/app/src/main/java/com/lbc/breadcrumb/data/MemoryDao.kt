package com.lbc.breadcrumb.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    /**
     * Re-saving a row we already hold -- a retried import, a redelivered share
     * -- is idempotent on the client-generated id.
     *
     * @Upsert, never @Insert(REPLACE). REPLACE deletes the old row without
     * firing delete triggers, and those triggers are what keep [MemoryFts] in
     * step: the old text would stay in the search index. @Upsert updates in
     * place, which fires them.
     */
    @Upsert
    suspend fun upsert(memory: Memory)

    @Upsert
    suspend fun upsertAll(memories: List<Memory>)

    @Update
    suspend fun update(memory: Memory)

    @Delete
    suspend fun delete(memory: Memory)

    @Query("SELECT * FROM memories ORDER BY capturedAt DESC")
    fun observeAll(): Flow<List<Memory>>

    @Query("SELECT * FROM memories ORDER BY capturedAt DESC")
    suspend fun getAll(): List<Memory>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: String): Memory?

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM memories")
    fun observeCount(): Flow<Int>

    /** Saved here but not yet on the server: queued, in flight, or refused. */
    @Query("SELECT COUNT(*) FROM memories WHERE syncState != 'SYNCED'")
    fun observeUnsyncedCount(): Flow<Int>

    /**
     * The upload queue: oldest first, so saves sync in the order they happened.
     *
     * PENDING only. A transient failure -- offline, server down -- puts a row
     * back to PENDING, while FAILED means the server refused this memory, and
     * sending the same bytes again would only be refused again. [retryFailed]
     * is how those get another chance, once something has changed.
     */
    /**
     * @param ocrDeadline an image saved after this is held back while OCR has
     *   still to read it: sending it now costs a Gemini pass on a memory whose
     *   words arrive seconds later, and another when they do. Images older
     *   than the deadline go regardless, so a read that never finishes cannot
     *   strand a memory off the server.
     */
    @Query(
        "SELECT * FROM memories WHERE syncState = 'PENDING' " +
            "AND NOT (type = 'IMAGE' AND extractedText IS NULL AND localUri IS NOT NULL " +
            "AND capturedAt > :ocrDeadline) " +
            "ORDER BY capturedAt ASC LIMIT :limit"
    )
    suspend fun pendingUploads(ocrDeadline: Long, limit: Int = 50): List<Memory>

    /**
     * UPLOADING means a request was in flight when the process died -- no
     * worker is running at the time this is called, since uploads are unique
     * work. The send was idempotent, so re-sending is safe.
     */
    @Query("UPDATE memories SET syncState = 'PENDING' WHERE syncState = 'UPLOADING'")
    suspend fun resetStaleUploads(): Int

    /** @return 0 when the row changed under us and should be left alone. */
    @Query("UPDATE memories SET syncState = 'UPLOADING' WHERE id = :id AND syncState = 'PENDING'")
    suspend fun markUploading(id: String): Int

    /**
     * Only from UPLOADING: if OCR text arrived mid-upload the row is PENDING
     * again, and marking it synced would strand the newer text off the server.
     */
    @Query(
        "UPDATE memories SET syncState = 'SYNCED', remoteId = :remoteId " +
            "WHERE id = :id AND syncState = 'UPLOADING'"
    )
    suspend fun markSynced(id: String, remoteId: String): Int

    @Query("UPDATE memories SET syncState = :state WHERE id = :id AND syncState = 'UPLOADING'")
    suspend fun markUploadEnded(id: String, state: SyncState): Int

    /**
     * Images whose picture is worth sending to be read (step 3.6): the memory
     * is already on the server, OCR found little or nothing in it, and it has
     * not been sent before.
     *
     * A screenshot full of text needs none of this -- its words are already
     * indexed, and a picture costs roughly ten times the tokens. What is left
     * is the photo of a whiteboard, the chart, the meme: the saves that are
     * otherwise almost unfindable.
     *
     * @param minChars below this much text, the picture is worth a look.
     * @param ocrDeadline images OCR has not read are only sent once this old,
     *   so a read still in flight is not pre-empted.
     */
    @Query(
        "SELECT * FROM memories WHERE type = 'IMAGE' AND localUri IS NOT NULL " +
            "AND imageSentAt IS NULL AND syncState = 'SYNCED' " +
            "AND LENGTH(COALESCE(extractedText, '')) < :minChars " +
            "AND (extractedText IS NOT NULL OR capturedAt <= :ocrDeadline) " +
            "ORDER BY capturedAt DESC LIMIT :limit"
    )
    suspend fun imagesAwaitingRead(minChars: Int, ocrDeadline: Long, limit: Int): List<Memory>

    @Query("UPDATE memories SET imageSentAt = :now WHERE id = :id")
    suspend fun markImageSent(id: String, now: Long)

    /** Queues every refused memory again -- after a fix, on demand. */
    @Query("UPDATE memories SET syncState = 'PENDING' WHERE syncState = 'FAILED'")
    suspend fun retryFailed(): Int

    @Query("SELECT * FROM memories WHERE type = :type ORDER BY capturedAt DESC")
    suspend fun getByType(type: MemoryType): List<Memory>

    /**
     * Local keyword search, newest first. Live, so a screenshot turns up as
     * soon as OCR has read it.
     *
     * Newest first rather than by relevance: FTS4 has no ranking function, and
     * this is the offline fast path -- ranked retrieval is the server's job
     * (steps 4.1 and 4.4).
     *
     * @param match an expression from [FtsQuery.matchExpression], never raw input.
     */
    @Query(
        "SELECT memories.* FROM memories " +
            "JOIN memories_fts ON memories.rowid = memories_fts.rowid " +
            "WHERE memories_fts MATCH :match " +
            "ORDER BY memories.capturedAt DESC"
    )
    fun search(match: String): Flow<List<Memory>>

    /**
     * Images whose text has not been read yet, newest first, so the one just
     * saved is read ahead of any backlog.
     *
     * @param exclude ids to pass over -- reads that already failed this run.
     */
    @Query(
        "SELECT * FROM memories WHERE type = 'IMAGE' AND extractedText IS NULL " +
            "AND id NOT IN (:exclude) ORDER BY capturedAt DESC LIMIT :limit"
    )
    suspend fun unreadImages(exclude: List<String>, limit: Int): List<Memory>

    @Query("SELECT COUNT(*) FROM memories WHERE type = 'IMAGE' AND extractedText IS NULL")
    fun observeUnreadImageCount(): Flow<Int>

    /**
     * An UPDATE rather than an upsert of the whole row: it cannot bring back a
     * memory deleted while its image was being read, nor overwrite columns
     * written in the meantime. It also only fills text that is still unread.
     */
    @Query(
        "UPDATE memories SET extractedText = :text, updatedAt = :now, " +
            // Text the server has not seen: queue the memory again, so what it
            // holds matches the phone. Reading nothing changes nothing, and
            // re-sending for that would cost a Gemini pass for no new words.
            "syncState = CASE WHEN :text != '' THEN 'PENDING' ELSE syncState END " +
            "WHERE id = :id AND extractedText IS NULL"
    )
    suspend fun setExtractedText(id: String, text: String, now: Long)

    @Query("DELETE FROM memories")
    suspend fun clear()
}
