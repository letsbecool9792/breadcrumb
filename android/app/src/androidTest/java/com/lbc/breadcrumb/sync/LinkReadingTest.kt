package com.lbc.breadcrumb.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.FtsQuery
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryDao
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.SyncState
import com.lbc.breadcrumb.net.PageMeta
import com.lbc.breadcrumb.net.PageReading
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The pass's bookkeeping, with a fake web. */
@RunWith(AndroidJUnit4::class)
class LinkReadingTest {

    private lateinit var db: BreadcrumbDatabase
    private lateinit var dao: MemoryDao

    /** URLs fetched, in any order. */
    private val fetched = mutableListOf<String>()
    private var online = true
    private var web: (String) -> PageReading = { PageReading.Unreadable("no such page") }

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

    private fun reading() = LinkReading(
        dao = dao,
        read = { url -> synchronized(fetched) { fetched += url }; web(url) },
        isOnline = { online },
        clock = { 7_000 },
    )

    private suspend fun link(id: String, text: String, title: String? = null, state: SyncState = SyncState.PENDING) =
        dao.upsert(Memory(id = id, type = MemoryType.LINK, rawText = text, title = title, syncState = state))

    @Test
    fun aBareLinkTakesItsPagesTitleAndIsFoundByItsDescription() = runBlocking {
        link("reel", "check this https://example.com/biryani")
        web = { PageReading.Read(PageMeta("The best biryani in Bengaluru", "Twelve places, ranked.", null)) }

        assertEquals(1, reading().readAll())

        val memory = dao.getById("reel")!!
        assertEquals("The best biryani in Bengaluru", memory.title)
        assertEquals("Twelve places, ranked.", memory.extractedText)
        // offline, the phone's own search finds it by the page
        assertEquals(listOf("reel"), dao.searchOnce(FtsQuery.matchExpression("biryani ranked")!!, 10).map { it.id })
    }

    @Test
    fun aTitleTheShareBroughtIsKept() = runBlocking {
        link("chrome", "https://example.com/a", title = "Chrome's own title")
        web = { PageReading.Read(PageMeta("The page's og:title", "What it is about", null)) }

        reading().readAll()

        assertEquals("Chrome's own title", dao.getById("chrome")!!.title)
        assertEquals("What it is about", dao.getById("chrome")!!.extractedText)
    }

    @Test
    fun aLinkAlreadyOnTheServerGoesAgainWithItsPage() = runBlocking {
        link("old", "https://example.com/old", state = SyncState.SYNCED)
        web = { PageReading.Read(PageMeta("Old link, now named", null, null)) }

        reading().readAll()

        // a title alone is new to the server too
        assertEquals(SyncState.PENDING, dao.getById("old")!!.syncState)
    }

    @Test
    fun aPageWithNothingIsReadOnceAndNeverFetchedAgain() = runBlocking {
        link("wall", "https://example.com/login-wall", state = SyncState.SYNCED)

        reading().readAll()
        reading().readAll()

        assertEquals(listOf("https://example.com/login-wall"), fetched)
        assertEquals("", dao.getById("wall")!!.extractedText)
        // nothing new: no second send for it
        assertEquals(SyncState.SYNCED, dao.getById("wall")!!.syncState)
    }

    @Test
    fun plainHttpIsFetchedAsHttps() = runBlocking {
        link("old-site", "http://example.com/page")

        reading().readAll()

        assertEquals(listOf("https://example.com/page"), fetched)
    }

    @Test
    fun whatIsNotAPageOnTheInternetIsNeverFetched() = runBlocking {
        link("router", "http://192.168.1.1/admin")
        link("dev", "http://localhost:3000/health")

        reading().readAll()

        assertTrue(fetched.isEmpty())
        assertEquals("", dao.getById("router")!!.extractedText)
    }

    @Test
    fun aDeadLinkWhileOnlineIsFinal() = runBlocking {
        link("dead", "https://this-domain-is-long-gone.example/page")
        web = { PageReading.Unreached("Unable to resolve host") }

        reading().readAll()

        assertEquals("", dao.getById("dead")!!.extractedText)
    }

    @Test
    fun losingTheNetworkLeavesLinksForNextTime() = runBlocking {
        link("waiting", "https://example.com/later")
        web = { PageReading.Unreached("timeout") }
        online = false

        reading().readAll()

        assertNull(dao.getById("waiting")!!.extractedText)
    }

    @Test
    fun onlyLinksAreRead() = runBlocking {
        dao.upsert(Memory(id = "note", type = MemoryType.TEXT, rawText = "no link here"))
        dao.upsert(Memory(id = "photo", type = MemoryType.IMAGE, hasLink = true, rawText = "https://example.com/menu"))

        reading().readAll()

        // a captioned photo is a photo: its caption's link is not what it is
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun aBacklogIsReadWhole() = runBlocking {
        repeat(9) { link("l$it", "https://example.com/$it") }
        web = { url -> PageReading.Read(PageMeta("Page ${url.substringAfterLast('/')}", null, null)) }

        assertEquals(9, reading().readAll())
        assertTrue(dao.unreadLinks(limit = 10).isEmpty())
    }
}
