package com.lbc.breadcrumb.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryDao
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.SyncState
import com.lbc.breadcrumb.net.MemoryUploadApi
import com.lbc.breadcrumb.net.UploadResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The queue's bookkeeping, against a fake server. Every failure here is one
 * the user would never see: a memory that quietly never syncs, one sent twice,
 * or one marked synced while the phone holds newer text.
 */
@RunWith(AndroidJUnit4::class)
class MemoryUploaderTest {

    private lateinit var db: BreadcrumbDatabase
    private lateinit var dao: MemoryDao

    /** Ids sent, in order. */
    private val sent = mutableListOf<String>()

    /** How many requests carried them: batching is the point, so it is asserted. */
    private var requests = 0
    private var answer: (Memory) -> UploadResult = { UploadResult.Stored(it.id, enriched = true, embedded = true) }

    private val api = object : MemoryUploadApi {
        override suspend fun upload(memories: List<Memory>): Map<String, UploadResult> {
            requests += 1
            sent += memories.map { it.id }
            return memories.associate { it.id to answer(it) }
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BreadcrumbDatabase::class.java).build()
        dao = db.memoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun saved(
        id: String,
        capturedAt: Long = 1_000,
        state: SyncState = SyncState.PENDING,
    ): Memory = Memory(
        id = id,
        type = MemoryType.TEXT,
        rawText = "saved text",
        capturedAt = capturedAt,
        syncState = state,
    ).also { dao.upsert(it) }

    private fun uploader(now: Long = 10_000, batchSize: Int = 2) =
        MemoryUploader(dao, api, batchSize = batchSize, ocrGraceMillis = 1_000, clock = { now })

    /** An image saved with its file copied in, before OCR has read it. */
    private suspend fun savedImage(id: String, capturedAt: Long): Memory = Memory(
        id = id,
        type = MemoryType.IMAGE,
        localUri = "file:///originals/$id.png",
        capturedAt = capturedAt,
    ).also { dao.upsert(it) }

    @Test
    fun sendsPendingMemoriesOldestFirstAndMarksThemSynced() = runBlocking {
        saved("old", capturedAt = 1_000)
        saved("new", capturedAt = 3_000)
        saved("mid", capturedAt = 2_000)

        val outcome = uploader().uploadPending()

        assertEquals(listOf("old", "mid", "new"), sent)
        assertEquals(UploadOutcome.Done(3), outcome)
        assertEquals(SyncState.SYNCED, dao.getById("new")!!.syncState)
        assertEquals("new", dao.getById("new")!!.remoteId)
    }

    @Test
    fun sendsNothingTwice() = runBlocking {
        saved("m")

        uploader().uploadPending()
        uploader().uploadPending()

        assertEquals(listOf("m"), sent)
    }

    @Test
    fun anOfflineUploadLeavesTheMemoryQueued() = runBlocking {
        saved("first", capturedAt = 1_000)
        saved("second", capturedAt = 2_000)
        answer = { UploadResult.Unavailable("connection refused") }

        val outcome = uploader().uploadPending()

        // one batch attempted, then it stops: the next would fail the same way
        assertEquals(1, requests)
        assertTrue(outcome is UploadOutcome.RetryLater)
        assertEquals(SyncState.PENDING, dao.getById("first")!!.syncState)
        assertEquals(SyncState.PENDING, dao.getById("second")!!.syncState)
    }

    @Test
    fun whatWasQueuedOfflineGoesOutOnTheNextPass() = runBlocking {
        saved("m")
        answer = { UploadResult.Unavailable("connection refused") }
        uploader().uploadPending()

        answer = { UploadResult.Stored(it.id, enriched = true, embedded = true) }
        uploader().uploadPending()

        // sent twice, stored once: the server dedupes on the memory's own id
        assertEquals(listOf("m", "m"), sent)
        assertEquals(SyncState.SYNCED, dao.getById("m")!!.syncState)
    }

    @Test
    fun aMemoryTheServerAsksUsToResendStaysQueuedWhileTheRestGoThrough() = runBlocking {
        // what a rate-limited model call looks like: stored, but not yet enriched
        saved("waiting", capturedAt = 1_000)
        saved("done", capturedAt = 2_000)
        answer = { memory ->
            if (memory.id == "waiting") {
                UploadResult.Unavailable("429 rate limited")
            } else {
                UploadResult.Stored(memory.id, enriched = true, embedded = true)
            }
        }

        val outcome = uploader().uploadPending()

        assertEquals(SyncState.PENDING, dao.getById("waiting")!!.syncState)
        assertEquals(SyncState.SYNCED, dao.getById("done")!!.syncState)
        assertTrue(outcome is UploadOutcome.RetryLater)
    }

    @Test
    fun severalMemoriesGoInOneRequest() = runBlocking {
        repeat(4) { saved("m$it", capturedAt = 1_000L + it) }

        uploader(batchSize = 10).uploadPending()

        assertEquals(4, sent.size)
        assertEquals(1, requests)
    }

    @Test
    fun aRefusedMemoryIsNotSentAgainButTheRestStillGo() = runBlocking {
        saved("bad", capturedAt = 1_000)
        saved("good", capturedAt = 2_000)
        answer = { memory ->
            if (memory.id == "bad") UploadResult.Rejected("HTTP 400") else UploadResult.Stored(memory.id, true, true)
        }

        uploader().uploadPending()
        uploader().uploadPending()

        assertEquals(SyncState.FAILED, dao.getById("bad")!!.syncState)
        assertEquals(SyncState.SYNCED, dao.getById("good")!!.syncState)
        assertEquals(listOf("bad", "good"), sent)
    }

    @Test
    fun retryFailedQueuesARefusedMemoryAgain() = runBlocking {
        saved("bad", state = SyncState.FAILED)

        dao.retryFailed()
        uploader().uploadPending()

        assertEquals(listOf("bad"), sent)
        assertEquals(SyncState.SYNCED, dao.getById("bad")!!.syncState)
    }

    @Test
    fun aMemoryLeftUploadingByADeadProcessIsSentAgain() = runBlocking {
        saved("stuck", state = SyncState.UPLOADING)

        uploader().uploadPending()

        assertEquals(listOf("stuck"), sent)
        assertEquals(SyncState.SYNCED, dao.getById("stuck")!!.syncState)
    }

    @Test
    fun textReadWhileUploadingKeepsTheMemoryQueued() = runBlocking {
        dao.upsert(Memory(id = "shot", type = MemoryType.IMAGE, localUri = "file:///shot.png"))
        // OCR finishes mid-request: the server now holds an older version
        answer = { memory ->
            runBlocking { dao.setExtractedText(memory.id, "Qualcomm SWE Internship", now = 2_000) }
            UploadResult.Stored(memory.id, enriched = true, embedded = true)
        }

        uploader().uploadPending()

        assertEquals(SyncState.PENDING, dao.getById("shot")!!.syncState)
    }

    @Test
    fun aMemoryDeletedMidPassIsSimplySkipped() = runBlocking {
        val memory = saved("undone")
        dao.delete(memory)

        val outcome = uploader().uploadPending()

        assertTrue(sent.isEmpty())
        assertEquals(UploadOutcome.Done(0), outcome)
    }

    @Test
    fun worksThroughMoreMemoriesThanOneBatch() = runBlocking {
        repeat(5) { saved("m$it", capturedAt = 1_000L + it) }

        uploader().uploadPending()

        assertEquals(5, sent.size)
        assertEquals(0, dao.pendingUploads(ocrDeadline = 0).size)
    }

    @Test
    fun aFreshImageWaitsForOcrRatherThanCostingTwoModelPasses() = runBlocking {
        savedImage("shot", capturedAt = 9_500)

        uploader(now = 10_000).uploadPending()

        assertTrue("an unread image should not be sent yet", sent.isEmpty())
    }

    @Test
    fun theImageGoesAsSoonAsItsTextIsRead() = runBlocking {
        savedImage("shot", capturedAt = 9_500)

        dao.setExtractedText("shot", "Qualcomm SWE Internship", now = 9_600)
        uploader(now = 10_000).uploadPending()

        assertEquals(listOf("shot"), sent)
    }

    @Test
    fun anImageOcrNeverFinishedIsSentAnyway() = runBlocking {
        // otherwise a read that always fails would keep the memory off the server for good
        savedImage("stubborn", capturedAt = 1_000)

        uploader(now = 10_000).uploadPending()

        assertEquals(listOf("stubborn"), sent)
    }
}
