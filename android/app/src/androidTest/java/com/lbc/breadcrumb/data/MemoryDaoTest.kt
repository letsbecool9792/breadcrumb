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
            capturedAt = 1_700_000_000_000,
            contentCreatedAt = 1_690_000_000_000,
            sourceApp = "com.whatsapp",
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
    fun pendingUploads_returnsOnlyUnsyncedOldestFirst() = runBlocking {
        dao.upsertAll(
            listOf(
                Memory(id = "p", type = MemoryType.TEXT, capturedAt = 2_000, syncState = SyncState.PENDING),
                Memory(id = "s", type = MemoryType.TEXT, capturedAt = 1_000, syncState = SyncState.SYNCED),
                Memory(id = "f", type = MemoryType.TEXT, capturedAt = 1_500, syncState = SyncState.FAILED),
                Memory(id = "u", type = MemoryType.TEXT, capturedAt = 3_000, syncState = SyncState.UPLOADING),
            )
        )

        val queued = dao.pendingUploads().map { it.id }

        // FAILED is retried, SYNCED is done, UPLOADING is already in flight
        assertEquals(listOf("f", "p"), queued)
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
