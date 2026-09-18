package com.lbc.breadcrumb.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDaoTest {

    private lateinit var db: BreadcrumbDatabase
    private lateinit var dao: MemoryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // in-memory: each test starts clean and nothing touches the real db
        db = Room.inMemoryDatabaseBuilder(context, BreadcrumbDatabase::class.java)
            .build()
        dao = db.memoryDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndReadBack_preservesEveryField() = runBlocking {
        val memory = Memory(
            id = "test-1",
            type = MemoryType.IMAGE,
            hasLink = true,
            capturedAt = 1_700_000_000_000,
            contentCreatedAt = 1_690_000_000_000,
            sourceApp = "com.whatsapp",
            sourceAppLabel = "WhatsApp",
            rawText = "shared caption",
            localUri = "file:///data/user/0/com.lbc.breadcrumb/files/img.jpg",
            extractedText = "Qualcomm SWE Internship",
            title = "Internship posting",
            summary = "A LinkedIn post about a Qualcomm internship",
            entitiesJson = """{"org":["Qualcomm"]}""",
            remoteId = null,
            syncState = SyncState.PENDING,
            updatedAt = 1_700_000_000_000,
        )

        dao.upsert(memory)
        val loaded = dao.getById("test-1")

        assertNotNull(loaded)
        assertEquals(memory, loaded)
    }

    @Test
    fun enumsRoundTripByName() = runBlocking {
        dao.upsert(Memory(id = "a", type = MemoryType.PDF, syncState = SyncState.FAILED))

        val loaded = dao.getById("a")!!

        assertEquals(MemoryType.PDF, loaded.type)
        assertEquals(SyncState.FAILED, loaded.syncState)
    }

    @Test
    fun defaults_generateIdAndMarkPending() = runBlocking {
        val memory = Memory(type = MemoryType.TEXT, rawText = "no id supplied")

        dao.upsert(memory)
        val loaded = dao.getById(memory.id)!!

        assertTrue(loaded.id.isNotBlank())
        assertEquals(SyncState.PENDING, loaded.syncState)
        assertNull(loaded.remoteId)
    }

    @Test
    fun observeAll_ordersNewestFirst() = runBlocking {
        dao.upsert(Memory(id = "old", type = MemoryType.TEXT, capturedAt = 1_000))
        dao.upsert(Memory(id = "new", type = MemoryType.TEXT, capturedAt = 3_000))
        dao.upsert(Memory(id = "mid", type = MemoryType.TEXT, capturedAt = 2_000))

        val ids = dao.observeAll().first().map { it.id }

        assertEquals(listOf("new", "mid", "old"), ids)
    }

    @Test
    fun upsert_replacesOnSameId() = runBlocking {
        dao.upsert(Memory(id = "dup", type = MemoryType.LINK, rawText = "first"))
        dao.upsert(Memory(id = "dup", type = MemoryType.LINK, rawText = "second"))

        assertEquals(1, dao.count())
        assertEquals("second", dao.getById("dup")!!.rawText)
    }

    @Test
    fun pendingUploads_returnsOnlyWhatIsWaitingToBeSent() = runBlocking {
        dao.upsertAll(
            listOf(
                Memory(id = "p2", type = MemoryType.TEXT, capturedAt = 2_000, syncState = SyncState.PENDING),
                Memory(id = "s", type = MemoryType.TEXT, capturedAt = 1_000, syncState = SyncState.SYNCED),
                Memory(id = "f", type = MemoryType.TEXT, capturedAt = 1_500, syncState = SyncState.FAILED),
                Memory(id = "u", type = MemoryType.TEXT, capturedAt = 3_000, syncState = SyncState.UPLOADING),
                Memory(id = "p1", type = MemoryType.TEXT, capturedAt = 500, syncState = SyncState.PENDING),
            )
        )

        val queued = dao.pendingUploads(ocrDeadline = 0).map { it.id }

        // SYNCED is done, UPLOADING is in flight, and FAILED was refused --
        // re-sending the same bytes would only be refused again (retryFailed
        // is how those get another chance)
        assertEquals(listOf("p1", "p2"), queued)
    }

    @Test
    fun readingTextQueuesTheMemoryToSyncAgain() = runBlocking {
        dao.upsert(Memory(id = "shot", type = MemoryType.IMAGE, syncState = SyncState.SYNCED))
        dao.upsert(Memory(id = "blank", type = MemoryType.IMAGE, syncState = SyncState.SYNCED))

        dao.setExtractedText("shot", "Qualcomm SWE Internship", now = 2_000)
        dao.setExtractedText("blank", "", now = 2_000)

        // the server has not seen those words yet
        assertEquals(SyncState.PENDING, dao.getById("shot")!!.syncState)
        // but reading nothing changes nothing worth a second Gemini pass
        assertEquals(SyncState.SYNCED, dao.getById("blank")!!.syncState)
    }

    @Test
    fun getByIds_returnsOnlyWhatThePhoneHolds() = runBlocking {
        dao.upsertAll(listOf(Memory(id = "a", type = MemoryType.TEXT), Memory(id = "b", type = MemoryType.LINK)))

        // "gone": ranked by the server, deleted here -- deletes do not sync
        val held = dao.getByIds(listOf("b", "gone", "a")).map { it.id }.toSet()

        assertEquals(setOf("a", "b"), held)
    }

    @Test
    fun searchOnce_isTheWordSearchNewestFirstAndCapped() = runBlocking {
        dao.upsertAll(
            listOf(
                Memory(id = "old", type = MemoryType.TEXT, capturedAt = 1_000, rawText = "Qualcomm internship"),
                Memory(id = "new", type = MemoryType.TEXT, capturedAt = 3_000, rawText = "Qualcomm referral"),
                Memory(id = "mid", type = MemoryType.TEXT, capturedAt = 2_000, rawText = "Qualcomm offer"),
                Memory(id = "other", type = MemoryType.TEXT, capturedAt = 4_000, rawText = "Naru's omakase"),
            )
        )

        val found = dao.searchOnce(FtsQuery.matchExpression("qualc")!!, limit = 2).map { it.id }

        assertEquals(listOf("new", "mid"), found)
    }

    @Test
    fun deleteEverywhere_removesTheRowAndRemembersTheDelete() = runBlocking {
        val memory = Memory(id = "gone", type = MemoryType.TEXT, rawText = "Qualcomm")
        dao.upsert(memory)

        dao.deleteEverywhere(memory, now = 5_000)

        assertNull(dao.getById("gone"))
        assertEquals(listOf("gone"), dao.pendingDeletes(limit = 10))
        // and it is gone from local search too
        assertTrue(dao.searchOnce(FtsQuery.matchExpression("qualcomm")!!, 10).isEmpty())
    }

    @Test
    fun restore_bringsTheMemoryBackAndForgetsTheDelete() = runBlocking {
        val memory = Memory(id = "back", type = MemoryType.TEXT, rawText = "Qualcomm")
        dao.upsert(memory)
        dao.deleteEverywhere(memory, now = 5_000)

        dao.restore(memory)

        assertEquals(memory, dao.getById("back"))
        assertEquals(0, dao.pendingDeleteCount())
        assertEquals(listOf("back"), dao.searchOnce(FtsQuery.matchExpression("qualcomm")!!, 10).map { it.id })
    }

    @Test
    fun clearEverywhere_remembersADeleteForEveryMemory() = runBlocking {
        dao.upsertAll(listOf(Memory(id = "a", type = MemoryType.TEXT), Memory(id = "b", type = MemoryType.LINK)))

        dao.clearEverywhere(now = 5_000)

        assertEquals(0, dao.count())
        assertEquals(setOf("a", "b"), dao.pendingDeletes(limit = 10).toSet())
    }

    @Test
    fun aCopiedSummaryIsFoundByLocalSearch() = runBlocking {
        dao.upsert(Memory(id = "shot", type = MemoryType.IMAGE, syncState = SyncState.SYNCED))

        dao.setEnrichment("shot", summary = "Comments joking about the TVA", kind = "screenshot", readText = null, now = 5_000)

        // offline, the phone now finds a picture by what the model said of it
        assertEquals(listOf("shot"), dao.searchOnce(FtsQuery.matchExpression("tva")!!, 10).map { it.id })
    }

    @Test
    fun enrichmentIsNotKeptForAMemoryQueuedAgain() = runBlocking {
        // re-queued between the ask and the answer: it will be read again, and asked about after
        dao.upsert(Memory(id = "moving", type = MemoryType.TEXT, syncState = SyncState.PENDING))

        assertEquals(0, dao.setEnrichment("moving", "stale", null, null, now = 5_000))
        assertNull(dao.getById("moving")!!.enrichedAt)
    }

    @Test
    fun searchableText_joinsPopulatedFieldsOnly() {
        val memory = Memory(
            type = MemoryType.IMAGE,
            title = "Internship posting",
            summary = null,
            rawText = "",
            extractedText = "Qualcomm SWE Internship",
        )

        assertEquals("Internship posting\nQualcomm SWE Internship", memory.searchableText)
    }
}
