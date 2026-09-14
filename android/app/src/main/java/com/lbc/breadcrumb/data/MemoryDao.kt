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

    @Query("DELETE FROM memories")
    suspend fun clear()
}
