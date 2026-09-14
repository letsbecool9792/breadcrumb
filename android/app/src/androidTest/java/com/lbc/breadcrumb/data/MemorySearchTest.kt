package com.lbc.breadcrumb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local keyword search against the device's own SQLite, since tokenizer
 * behaviour -- case and accent folding -- is SQLite's, not ours. The index is
 * kept in step by triggers, and a stale index fails silently, so every kind of
 * write is covered.
 */
@RunWith(AndroidJUnit4::class)
class MemorySearchTest {

    private lateinit var db: BreadcrumbDatabase
    private lateinit var dao: MemoryDao

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

    private suspend fun search(input: String): List<String> =
        dao.search(FtsQuery.matchExpression(input)!!).first().map { it.id }

    @Test
    fun findsAWordThatAppearsOnlyInsideAScreenshot() = runBlocking {
        dao.upsert(Memory(id = "shot", type = MemoryType.IMAGE, extractedText = "Qualcomm | Software Engineering Intern"))
        dao.upsert(Memory(id = "note", type = MemoryType.TEXT, rawText = "call the bank"))

        assertEquals(listOf("shot"), search("qualcomm"))
    }

    @Test
    fun searchesTitleSummaryAndSharedText() = runBlocking {
        dao.upsert(Memory(id = "t", type = MemoryType.LINK, title = "Compose performance"))
        dao.upsert(Memory(id = "s", type = MemoryType.PDF, summary = "Distributed systems notes"))
        dao.upsert(Memory(id = "r", type = MemoryType.TEXT, rawText = "Naru's in Indiranagar"))

        assertEquals(listOf("t"), search("compose"))
        assertEquals(listOf("s"), search("distributed"))
        assertEquals(listOf("r"), search("indiranagar"))
    }

    @Test
    fun findsTextOcrAddsAfterTheSave() = runBlocking {
        dao.upsert(Memory(id = "shot", type = MemoryType.IMAGE))
        assertEquals(emptyList<String>(), search("hackathon"))

        dao.setExtractedText("shot", "HackMIT hackathon 2026", now = 2_000)

        assertEquals(listOf("shot"), search("hackathon"))
    }

    @Test
    fun matchesTheStartOfAWord() = runBlocking {
        dao.upsert(Memory(id = "m", type = MemoryType.IMAGE, extractedText = "Summer internships 2026"))

        assertEquals(listOf("m"), search("intern"))
        assertEquals(emptyList<String>(), search("ternship"))
    }

    @Test
    fun everyWordMustMatch() = runBlocking {
        dao.upsert(Memory(id = "both", type = MemoryType.TEXT, rawText = "Qualcomm internship"))
        dao.upsert(Memory(id = "one", type = MemoryType.TEXT, rawText = "Qualcomm earnings call"))

        assertEquals(listOf("both"), search("qualcomm intern"))
    }

    @Test
    fun ignoresCaseAndAccents() = runBlocking {
        dao.upsert(Memory(id = "m", type = MemoryType.TEXT, rawText = "Café Coffee Day, ÉCOLE"))

        assertEquals(listOf("m"), search("cafe"))
        assertEquals(listOf("m"), search("CAFÉ"))
        assertEquals(listOf("m"), search("école"))
    }

    @Test
    fun findsWordsAroundAnApostrophe() = runBlocking {
        dao.upsert(Memory(id = "m", type = MemoryType.TEXT, rawText = "Naru's omakase"))

        assertEquals(listOf("m"), search("Naru's"))
        assertEquals(listOf("m"), search("naru"))
    }

    @Test
    fun ftsSyntaxInTheInputDoesNotBreakTheQuery() = runBlocking {
        dao.upsert(Memory(id = "m", type = MemoryType.TEXT, rawText = "cats or dogs near the park, title: pets"))

        // each of these would be a syntax error, or mean something else, if passed raw
        assertEquals(listOf("m"), search("cats OR dogs"))
        assertEquals(listOf("m"), search("NEAR(park)"))
        assertEquals(listOf("m"), search("-\"cats"))
        assertEquals(listOf("m"), search("title:pets"))
    }

    @Test
    fun theSourceAppIsNotSearchedAsText() = runBlocking {
        // provenance is a filter (step 4.3), not something to match words against
        dao.upsert(Memory(id = "m", type = MemoryType.TEXT, rawText = "hello", sourceAppLabel = "WhatsApp"))

        assertEquals(emptyList<String>(), search("whatsapp"))
    }

    @Test
    fun newestFirst() = runBlocking {
        dao.upsert(Memory(id = "old", type = MemoryType.TEXT, rawText = "maples in kyoto", capturedAt = 1_000))
        dao.upsert(Memory(id = "new", type = MemoryType.TEXT, rawText = "kyoto hotel", capturedAt = 3_000))
        dao.upsert(Memory(id = "mid", type = MemoryType.TEXT, rawText = "kyoto trains", capturedAt = 2_000))

        assertEquals(listOf("new", "mid", "old"), search("kyoto"))
    }

    @Test
    fun aDeletedMemoryIsNoLongerFound() = runBlocking {
        val memory = Memory(id = "m", type = MemoryType.TEXT, rawText = "temporary")
        dao.upsert(memory)

        dao.delete(memory)

        assertEquals(emptyList<String>(), search("temporary"))
    }

    @Test
    fun clearingEverythingEmptiesTheIndexToo() = runBlocking {
        dao.upsert(Memory(id = "m", type = MemoryType.TEXT, rawText = "temporary"))

        dao.clear()
        dao.upsert(Memory(id = "n", type = MemoryType.TEXT, rawText = "temporary"))

        assertEquals(listOf("n"), search("temporary"))
    }

    @Test
    fun upsertingTheSameIdReplacesItsIndexedText() = runBlocking {
        // an @Insert(REPLACE) upsert leaves the old text in the index: REPLACE
        // deletes without firing the triggers that would remove it
        dao.upsert(Memory(id = "m", type = MemoryType.TEXT, rawText = "first draft"))
        dao.upsert(Memory(id = "m", type = MemoryType.TEXT, rawText = "second draft"))

        assertEquals(emptyList<String>(), search("first"))
        assertEquals(listOf("m"), search("second"))
        assertEquals(listOf("m"), search("draft"))
    }

    @Test
    fun openResultsUpdateWhenAMatchIsSaved() = runBlocking {
        val emissions = Channel<List<String>>(Channel.UNLIMITED)
        val watching = launch(Dispatchers.IO) {
            dao.search(FtsQuery.matchExpression("kyoto")!!).collect { rows -> emissions.send(rows.map { it.id }) }
        }
        try {
            assertEquals(emptyList<String>(), withTimeout(5_000) { emissions.receive() })

            dao.upsert(Memory(id = "m", type = MemoryType.TEXT, rawText = "kyoto"))

            // Room may re-emit the empty result before the write lands; wait past it
            val updated = withTimeout(5_000) {
                var ids = emissions.receive()
                while (ids.isEmpty()) ids = emissions.receive()
                ids
            }
            assertEquals(listOf("m"), updated)
        } finally {
            watching.cancel()
        }
    }
}
