package com.lbc.breadcrumb.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.MemoryDao
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.OriginalStore
import com.lbc.breadcrumb.data.SyncState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * What the app's own sheet writes when only words were put in. (Picked files
 * go through MediaCapture, the same as a share, and need a provider's URI.)
 */
@RunWith(AndroidJUnit4::class)
class WrittenCaptureTest {

    private lateinit var db: BreadcrumbDatabase
    private lateinit var dao: MemoryDao
    private lateinit var capture: WrittenCapture
    private lateinit var sandbox: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BreadcrumbDatabase::class.java).build()
        dao = db.memoryDao()
        // cacheDir, never the real filesDir/originals: tests must not touch saved memories
        sandbox = File(context.cacheDir, "written-test").apply { deleteRecursively(); mkdirs() }
        capture = WrittenCapture(context, dao, OriginalStore(context, File(sandbox, "originals")))
    }

    @After
    fun tearDown() {
        db.close()
        sandbox.deleteRecursively()
    }

    @Test
    fun aThoughtIsKeptAsANoteFromThePersonThemselves() = runBlocking {
        val saved = capture.save("  Ask Priya about the flat in Indiranagar  ", files = emptyList(), now = 5_000)

        val memory = dao.getById(saved.single().id)!!
        assertEquals(MemoryType.TEXT, memory.type)
        assertEquals("Ask Priya about the flat in Indiranagar", memory.rawText)
        // no app sent it
        assertNull(memory.sourceApp)
        assertNull(memory.sourceAppLabel)
        assertNull(memory.note)
        assertEquals(SyncState.PENDING, memory.syncState)
        assertEquals(5_000L, memory.capturedAt)
    }

    @Test
    fun aPastedLinkIsKeptAsALink() = runBlocking {
        val saved = capture.save("the rent control explainer https://example.com/rent", files = emptyList())

        val memory = saved.single()
        assertEquals(MemoryType.LINK, memory.type)
        assertTrue(memory.hasLink)
    }

    @Test
    fun nothingWrittenKeepsNothing() = runBlocking {
        assertTrue(capture.save("   ", files = emptyList()).isEmpty())
        assertEquals(0, dao.count())
    }
}
