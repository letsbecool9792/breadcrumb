package com.lbc.breadcrumb.sync

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryDao
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.OriginalStore
import com.lbc.breadcrumb.data.SyncState
import com.lbc.breadcrumb.net.Enrichment
import com.lbc.breadcrumb.net.MemoryUploadApi
import com.lbc.breadcrumb.net.OutgoingImage
import com.lbc.breadcrumb.net.UploadResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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

    /** Memory ids whose picture was sent, in order. */
    private val pictures = mutableListOf<String>()
    private var pictureRequests = 0
    private var pictureAnswer: (String) -> UploadResult =
        { UploadResult.Stored(it, enriched = true, embedded = true) }

    private val api = object : MemoryUploadApi {
        override suspend fun upload(memories: List<Memory>): Map<String, UploadResult> {
            requests += 1
            sent += memories.map { it.id }
            return memories.associate { it.id to answer(it) }
        }

        override suspend fun uploadImages(images: List<OutgoingImage>): Map<String, UploadResult> {
            pictureRequests += 1
            pictures += images.map { it.memoryId }
            return images.associate { it.memoryId to pictureAnswer(it.memoryId) }
        }

        override suspend fun enrichment(ids: List<String>): Map<String, Enrichment>? {
            asked += ids
            return if (serverAnswers) held.filterKeys { it in ids } else null
        }

        override suspend fun delete(ids: List<String>): Boolean {
            if (!serverAnswers) return false
            deleted += ids
            return true
        }
    }

    /** What the fake server made of the memories it holds. */
    private val held = mutableMapOf<String, Enrichment>()
    private val asked = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private var serverAnswers = true

    private lateinit var sandbox: File
    private lateinit var store: OriginalStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BreadcrumbDatabase::class.java).build()
        dao = db.memoryDao()
        // cacheDir, never the real filesDir/originals: tests must not touch saved memories
        sandbox = File(context.cacheDir, "uploader-test").apply { deleteRecursively(); mkdirs() }
        store = OriginalStore(context, File(sandbox, "originals"))
    }

    @After
    fun tearDown() {
        db.close()
        sandbox.deleteRecursively()
    }

    /** An image memory already on the server, with its file stored. */
    private suspend fun readableImage(id: String, text: String?, capturedAt: Long = 1_000): Memory {
        val source = File(sandbox, "$id-source.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stored = store.copyIn(Uri.fromFile(source), id, "png")
        return Memory(
            id = id,
            type = MemoryType.IMAGE,
            capturedAt = capturedAt,
            localUri = store.uriFor(stored),
            extractedText = text,
            syncState = SyncState.SYNCED,
        ).also { dao.upsert(it) }
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

    private fun uploader(now: Long = 10_000, batchSize: Int = 2, held: () -> Collection<String> = { emptySet() }) =
        MemoryUploader(
            dao = dao,
            api = api,
            store = store,
            batchSize = batchSize,
            imageBatchSize = 2,
            ocrGraceMillis = 1_000,
            clock = { now },
            // the bitmap work is ImageForUpload's, and has its own tests
            prepareImage = { file -> if (file.exists()) file.readBytes() else null },
            held = held,
        )

    @Test
    fun aMemoryWhoseSheetIsStillOpenWaits() = runBlocking {
        saved("open", capturedAt = 1_000)
        saved("closed", capturedAt = 2_000)
        saved("closed-too", capturedAt = 3_000)
        val holds = mutableSetOf("open")

        // batches of one, so the held memory is passed over batch after batch without spinning
        val first = uploader(batchSize = 1, held = { holds }).uploadPending()

        assertEquals(UploadOutcome.Done(2), first)
        assertEquals(listOf("closed", "closed-too"), sent)
        assertEquals(SyncState.PENDING, dao.getById("open")!!.syncState)

        // the sheet closed, and a note may have been written: now it goes, once
        holds.clear()
        uploader(held = { holds }).uploadPending()
        assertEquals(listOf("closed", "closed-too", "open"), sent)
    }

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
    fun sendsThePictureOfAnImageOcrCouldBarelyRead() = runBlocking {
        readableImage("whiteboard", text = "")

        val outcome = uploader().uploadImages()

        assertEquals(listOf("whiteboard"), pictures)
        assertEquals(UploadOutcome.Done(1), outcome)
        assertNotNull(dao.getById("whiteboard")!!.imageSentAt)
    }

    @Test
    fun leavesAScreenshotFullOfTextAlone() = runBlocking {
        // its words are already indexed, and a picture costs about ten times the tokens
        readableImage("article", text = "Qualcomm is hiring software engineering interns in Bengaluru. ".repeat(3))

        uploader().uploadImages()

        assertTrue("a text-heavy screenshot should not be sent", pictures.isEmpty())
        assertNull(dao.getById("article")!!.imageSentAt)
    }

    @Test
    fun neverSendsTheSamePictureTwice() = runBlocking {
        readableImage("whiteboard", text = "")

        uploader().uploadImages()
        uploader().uploadImages()

        assertEquals(listOf("whiteboard"), pictures)
    }

    @Test
    fun severalPicturesGoInOneRequest() = runBlocking {
        readableImage("a", text = "", capturedAt = 1_000)
        readableImage("b", text = null, capturedAt = 900)

        uploader().uploadImages()

        assertEquals(2, pictures.size)
        assertEquals(1, pictureRequests)
    }

    @Test
    fun waitsForOcrBeforeDecidingAPictureIsWorthReading() = runBlocking {
        // unread, and saved a moment ago: OCR may still be about to find its words
        readableImage("justSaved", text = null, capturedAt = 9_500)

        uploader(now = 10_000).uploadImages()

        assertTrue(pictures.isEmpty())
    }

    @Test
    fun aPictureIsOnlySentOnceItsMemoryIsOnTheServer() = runBlocking {
        val waiting = readableImage("waiting", text = "")
        dao.upsert(waiting.copy(syncState = SyncState.PENDING))

        uploader().uploadImages()

        // the server reads a picture for a memory it holds; this one it does not
        assertTrue(pictures.isEmpty())
    }

    @Test
    fun aPictureTheServerCannotTakeIsNotOfferedForever() = runBlocking {
        readableImage("odd", text = "")
        pictureAnswer = { UploadResult.Rejected("HTTP 400 unsupported image") }

        uploader().uploadImages()
        uploader().uploadImages()

        assertEquals(listOf("odd"), pictures)
        assertNotNull(dao.getById("odd")!!.imageSentAt)
    }

    @Test
    fun aPictureStaysQueuedWhenTheServerIsBusy() = runBlocking {
        readableImage("whiteboard", text = "")
        pictureAnswer = { UploadResult.Unavailable("429 rate limited") }

        val outcome = uploader().uploadImages()

        assertTrue(outcome is UploadOutcome.RetryLater)
        assertNull(dao.getById("whiteboard")!!.imageSentAt)
    }

    @Test
    fun anImageWhoseFileIsGoneIsNotRetriedForever() = runBlocking {
        val memory = readableImage("lost", text = "")
        store.delete(memory)

        uploader().uploadImages()

        assertTrue(pictures.isEmpty())
        assertNotNull(dao.getById("lost")!!.imageSentAt)
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

    // --- deletes (step 4.6) -------------------------------------------------------

    @Test
    fun aDeleteOnThePhoneReachesTheServerOnce() = runBlocking {
        val memory = saved("gone", state = SyncState.SYNCED)
        dao.deleteEverywhere(memory, now = 5_000)

        uploader().sendDeletes()
        uploader().sendDeletes()

        assertEquals(listOf("gone"), deleted)
        assertEquals(0, dao.pendingDeleteCount())
    }

    @Test
    fun aDeleteTheServerDidNotTakeIsKeptForNextTime() = runBlocking {
        dao.deleteEverywhere(saved("gone"), now = 5_000)
        serverAnswers = false

        val outcome = uploader().sendDeletes()

        assertTrue(outcome is UploadOutcome.RetryLater)
        assertEquals(1, dao.pendingDeleteCount())
    }

    @Test
    fun aDeleteTakenBackIsNeverSent() = runBlocking {
        val memory = saved("back", state = SyncState.SYNCED)
        dao.deleteEverywhere(memory, now = 5_000)
        dao.restore(memory.copy(syncState = SyncState.PENDING))

        uploader().sendDeletes()
        uploader().uploadPending()

        assertTrue(deleted.isEmpty())
        // and it goes up again, which costs nothing if the server still has it
        assertEquals(listOf("back"), sent)
    }

    // --- enrichment copied back (step 4.6) ------------------------------------------

    @Test
    fun whatTheModelMadeOfAMemoryIsKeptOnThePhone() = runBlocking {
        saved("shot", state = SyncState.SYNCED)
        held["shot"] = Enrichment("shot", summary = "Comments about the TVA", kind = "screenshot", readText = "A Reddit thread")

        uploader(now = 7_000).fetchEnrichment()

        val stored = dao.getById("shot")!!
        assertEquals("Comments about the TVA", stored.summary)
        assertEquals("screenshot", stored.kind)
        assertEquals("A Reddit thread", stored.readText)
        assertEquals(7_000L, stored.enrichedAt)
    }

    @Test
    fun aMemoryIsAskedAboutOnceUntilItIsSentAgain() = runBlocking {
        // held by the server, but with nothing to say about it yet
        saved("thin", state = SyncState.SYNCED)

        uploader().fetchEnrichment()
        uploader().fetchEnrichment()
        assertEquals(listOf("thin"), asked)

        // sent again -- new text, say -- and the copy is stale until asked for
        dao.update(dao.getById("thin")!!.copy(syncState = SyncState.UPLOADING))
        dao.markSynced("thin", remoteId = "thin")
        uploader().fetchEnrichment()

        assertEquals(listOf("thin", "thin"), asked)
    }

    @Test
    fun onlyMemoriesTheServerHoldsAreAskedAbout() = runBlocking {
        saved("queued", state = SyncState.PENDING)
        saved("synced", state = SyncState.SYNCED)

        uploader().fetchEnrichment()

        assertEquals(listOf("synced"), asked)
    }

    @Test
    fun aServerOutOfReachLeavesEnrichmentToAskForLater() = runBlocking {
        saved("synced", state = SyncState.SYNCED)
        serverAnswers = false

        val outcome = uploader().fetchEnrichment()

        assertTrue(outcome is UploadOutcome.RetryLater)
        assertNull(dao.getById("synced")!!.enrichedAt)
    }

    @Test
    fun aPictureReadMakesTheCopyStale() = runBlocking {
        saved("photo", state = SyncState.SYNCED)
        uploader(now = 7_000).fetchEnrichment()
        assertNotNull(dao.getById("photo")!!.enrichedAt)

        dao.markImageSent("photo", now = 8_000)

        assertNull(dao.getById("photo")!!.enrichedAt)
    }
}
