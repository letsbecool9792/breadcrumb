package com.lbc.breadcrumb.net

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The fetch, against a local server standing in for the web. */
class PageReaderTest {

    private lateinit var server: MockWebServer
    private val reader = PageReader(timeoutMillis = 3_000)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun page(body: String, code: Int = 200, type: String = "text/html; charset=utf-8") {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Content-Type", type).body(body).build())
    }

    private val url get() = server.url("/article").toString()

    @Test
    fun readsAPagesHead() = runBlocking {
        page("""<html><head><meta property="og:title" content="Rent control, explained"></head><body></body></html>""")

        val reading = reader.read(url)

        assertEquals(PageReading.Read(PageMeta("Rent control, explained", null, null)), reading)
    }

    @Test
    fun asksAsABrowserWouldAndSaysWhoItIs() = runBlocking {
        page("<title>x y z</title>")

        reader.read(url)

        val request = server.takeRequest()
        val agent = request.headers["User-Agent"].orEmpty()
        assertTrue(agent, agent.startsWith("Mozilla/5.0") && agent.contains("Breadcrumb"))
        assertTrue(request.headers["Accept"].orEmpty().startsWith("text/html"))
    }

    @Test
    fun followsARedirect() = runBlocking {
        server.enqueue(MockResponse.Builder().code(301).addHeader("Location", server.url("/moved").toString()).build())
        page("<title>Where it went</title>")

        assertEquals(PageReading.Read(PageMeta("Where it went", null, null)), reader.read(url))
    }

    @Test
    fun anErrorPageIsUnreadable() = runBlocking {
        page("<title>Not here</title>", code = 404)

        assertEquals(PageReading.Unreadable("HTTP 404"), reader.read(url))
    }

    @Test
    fun aFileThatIsNotAPageIsUnreadable() = runBlocking {
        page("%PDF-1.7", type = "application/pdf")

        assertTrue(reader.read(url) is PageReading.Unreadable)
    }

    @Test
    fun aPageWithNothingInItsHeadIsUnreadable() = runBlocking {
        page("<html><body>just a body</body></html>")

        assertEquals(PageReading.Unreadable("nothing in its head"), reader.read(url))
    }

    @Test
    fun onlyTheHeadIsRead() = runBlocking {
        // the title is in the head; a second one far down the body must never be reached
        page("<head><title>The real one</title></head><body>" + "x".repeat(600_000) + "<title>Too far</title></body>")

        assertEquals(PageReading.Read(PageMeta("The real one", null, null)), reader.read(url))
    }

    @Test
    fun aServerThatIsNotThereIsUnreached() = runBlocking {
        val gone = url
        server.close()

        assertTrue(reader.read(gone) is PageReading.Unreached)
    }

    @Test
    fun somethingThatIsNotAUrlIsUnreadable() = runBlocking {
        assertEquals(PageReading.Unreadable("not a URL"), reader.read("not a url at all"))
    }
}
