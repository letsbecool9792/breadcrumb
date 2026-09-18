package com.lbc.breadcrumb.ocr

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * The queue's bookkeeping, with a fake reader. Every failure mode here is
 * silent -- an image that is never read, a deleted memory brought back, a
 * failure retried forever -- so each gets a test.
 */
@RunWith(AndroidJUnit4::class)
class OcrQueueTest {

    private lateinit var db: BreadcrumbDatabase
    private lateinit var dao: MemoryDao
    private lateinit var sandbox: File
    private lateinit var store: OriginalStore

    /** Files the fake reader was asked to read, in order. */
    private val reads = mutableListOf<String>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BreadcrumbDatabase::class.java).build()
        dao = db.memoryDao()
        // cacheDir, never the real filesDir/originals: tests must not touch saved memories
        sandbox = File(context.cacheDir, "ocr-queue-test").apply { deleteRecursively(); mkdirs() }
        store = OriginalStore(context, File(sandbox, "originals"))
    }

    @After
    fun tearDown() {
        db.close()
        sandbox.deleteRecursively()
    }

    /** PDFs the fake document reader was asked to read, in order. */
    private val pdfReads = mutableListOf<String>()

    private fun queue(
        batchSize: Int = 10,
        readPdf: suspend (File) -> String = { "" },
        read: suspend (File) -> String,
    ) = OcrQueue(
        dao,
        store,
        OcrReader { file -> reads += file.nameWithoutExtension; read(file) },
        batchSize,
        documents = OcrReader { file -> pdfReads += file.nameWithoutExtension; readPdf(file) },
    )

    /** An image memory with a real stored file behind it. */
    private suspend fun image(id: String, capturedAt: Long = 1_000): Memory = stored(id, MemoryType.IMAGE, "png", capturedAt)

    private suspend fun stored(id: String, type: MemoryType, extension: String, capturedAt: Long = 1_000): Memory {
        val source = File(sandbox, "$id-source.$extension").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stored = store.copyIn(Uri.fromFile(source), id, extension)
        return Memory(id = id, type = type, capturedAt = capturedAt, localUri = store.uriFor(stored))
            .also { dao.upsert(it) }
    }

    @Test
    fun drain_readsAPdfWithTheDocumentReader() = runBlocking {
        stored("notes", MemoryType.PDF, "pdf")
        image("shot")

        queue(readPdf = { "Data Structures, week 6" }) { "Qualcomm SWE Internship" }.drain()

        // each kind to its own reader, never the other's
        assertEquals(listOf("notes"), pdfReads)
        assertEquals(listOf("shot"), reads)
        assertEquals("Data Structures, week 6", dao.getById("notes")!!.extractedText)
    }

    @Test
    fun drain_sendsAPdfAlreadySyncedAgainOnceItsTextIsRead() = runBlocking {
        // saved before PDFs were read: on the server under its filename alone
        dao.upsert(stored("old-notes", MemoryType.PDF, "pdf").copy(syncState = SyncState.SYNCED))

        queue(readPdf = { "Data Structures, week 6" }) { "" }.drain()

        assertEquals(SyncState.PENDING, dao.getById("old-notes")!!.syncState)
    }

    @Test
    fun drain_storesTheTextOfUnreadImages() = runBlocking {
        image("shot")

        queue { "Qualcomm SWE Internship" }.drain()

        assertEquals("Qualcomm SWE Internship", dao.getById("shot")!!.extractedText)
    }

    @Test
    fun drain_readsNewestFirst() = runBlocking {
        image("old", capturedAt = 1_000)
        image("new", capturedAt = 3_000)
        image("mid", capturedAt = 2_000)

        queue { "text" }.drain()

        assertEquals(listOf("new", "mid", "old"), reads)
    }

    @Test
    fun drain_leavesEverythingButImagesAndPdfsAlone() = runBlocking {
        dao.upsert(Memory(id = "t", type = MemoryType.TEXT, rawText = "hello"))
        dao.upsert(Memory(id = "l", type = MemoryType.LINK, rawText = "https://example.com"))

        queue { "text" }.drain()

        assertTrue(reads.isEmpty())
        assertTrue(pdfReads.isEmpty())
        listOf("t", "l").forEach { assertNull(dao.getById(it)!!.extractedText) }
    }

    @Test
    fun drain_neverReadsAnImageTwice() = runBlocking {
        image("blank")
        val queue = queue { "" }

        queue.drain()
        queue.drain()

        // no text is a final answer, stored as empty rather than left unread
        assertEquals("", dao.getById("blank")!!.extractedText)
        assertEquals(listOf("blank"), reads)
    }

    @Test
    fun drain_treatsAMissingOriginalAsNoText() = runBlocking {
        // the sample memories' made-up paths look exactly like this
        dao.upsert(Memory(id = "gone", type = MemoryType.IMAGE, localUri = "file:///sample/internship.png"))

        queue { "text" }.drain()

        assertTrue(reads.isEmpty())
        assertEquals("", dao.getById("gone")!!.extractedText)
    }

    @Test
    fun drain_leavesAFailedReadUnreadAndDoesNotRetryIt() = runBlocking {
        image("broken")
        val queue = queue { throw IOException("recognizer fell over") }

        queue.drain()
        queue.drain()

        // still unread, so a later process start tries again
        assertNull(dao.getById("broken")!!.extractedText)
        assertEquals(listOf("broken"), reads)
    }

    @Test
    fun drain_getsPastMoreFailuresThanFitInABatch() = runBlocking {
        repeat(5) { image("broken-$it", capturedAt = 2_000L + it) }
        image("fine", capturedAt = 1_000)

        queue(batchSize = 2) { file ->
            if (file.name.startsWith("broken")) throw IOException("no") else "read"
        }.drain()

        assertEquals("read", dao.getById("fine")!!.extractedText)
    }

    @Test
    fun drain_doesNotBringBackAMemoryDeletedWhileBeingRead() = runBlocking {
        val memory = image("undone")

        // the user taps Undo while the image is being read
        queue { dao.delete(memory); "text" }.drain()

        assertNull(dao.getById("undone"))
    }

    @Test
    fun drain_neverOverwritesTextAlreadyThere() = runBlocking {
        val memory = image("filled")
        dao.upsert(memory.copy(extractedText = "already here"))

        queue { "from ocr" }.drain()

        assertTrue(reads.isEmpty())
        assertEquals("already here", dao.getById("filled")!!.extractedText)
    }

    @Test
    fun drain_releasesTheReaderWhenDone() = runBlocking {
        image("shot")
        var released = 0
        val reader = object : OcrReader {
            override suspend fun read(file: File) = "text"
            override fun release() { released++ }
        }

        OcrQueue(dao, store, reader).drain()

        assertEquals(1, released)
    }

    @Test
    fun drain_asksForAnUploadPassEvenWhenAPictureHeldNoText() = runBlocking {
        // an image waits for OCR before it is sent, and one with no words is the
        // very case whose picture gets read -- so an empty read must still let it go
        image("blank")
        var passes = 0

        OcrQueue(dao, store, OcrReader { "" }, onRead = { passes += 1 }).drain()

        assertEquals(1, passes)
    }

    @Test
    fun run_readsImagesSavedWhileItWatches() = runBlocking {
        val watching = launch(Dispatchers.IO) { queue { "read later" }.run() }
        try {
            image("saved-later")

            val read = withTimeout(5_000) {
                dao.observeAll().first { rows -> rows.singleOrNull()?.extractedText != null }
            }

            assertEquals("read later", read.single().extractedText)
        } finally {
            watching.cancel()
        }
    }
}
