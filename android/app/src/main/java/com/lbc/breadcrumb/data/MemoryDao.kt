package com.lbc.breadcrumb.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    /**
     * REPLACE rather than ABORT so re-saving a row we already hold -- a retried
     * import, a redelivered share -- is idempotent on the client-generated id.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: Memory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

    /** The upload queue: oldest first, so saves sync in the order they happened. */
    @Query(
        "SELECT * FROM memories WHERE syncState IN ('PENDING', 'FAILED') " +
            "ORDER BY capturedAt ASC LIMIT :limit"
    )
    suspend fun pendingUploads(limit: Int = 50): List<Memory>

    @Query("SELECT * FROM memories WHERE type = :type ORDER BY capturedAt DESC")
    suspend fun getByType(type: MemoryType): List<Memory>

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
        "UPDATE memories SET extractedText = :text, updatedAt = :now " +
            "WHERE id = :id AND extractedText IS NULL"
    )
    suspend fun setExtractedText(id: String, text: String, now: Long)

    @Query("DELETE FROM memories")
    suspend fun clear()
}
