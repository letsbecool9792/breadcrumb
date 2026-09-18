package com.lbc.breadcrumb.net

import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.SyncState
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The health check against a real HTTP server, so OkHttp's own failures --
 * refused, timed out -- are the real ones rather than simulated.
 *
 * The Breadcrumb body here is the contract with server/src/app.ts; its test
 * asserts the same shape.
 */
class ServerClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun client(timeoutMillis: Long = 5_000) =
        ServerClient(server.url("").toString().trimEnd('/'), healthTimeoutMillis = timeoutMillis)

    private fun respond(code: Int, body: String) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    private fun memory(id: String) = Memory(id = id, type = MemoryType.TEXT, rawText = "saved text")

    private fun reason(status: ServerStatus): String {
        assertTrue("expected Unreachable, got $status", status is ServerStatus.Unreachable)
        return (status as ServerStatus.Unreachable).reason
    }

    @Test
    fun `breadcrumb saying ok is reachable`() = runBlocking {
        respond(200, """{"service":"breadcrumb","status":"ok"}""")

        assertEquals(ServerStatus.Reachable, client().health())
        assertEquals("/health", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `fields the app does not know yet are ignored`() = runBlocking {
        respond(200, """{"service":"breadcrumb","status":"ok","version":"0.2.0"}""")

        assertEquals(ServerStatus.Reachable, client().health())
    }

    @Test
    fun `another server holding the port is not mistaken for breadcrumb`() = runBlocking {
        respond(200, """{"status":"ok"}""")
        assertTrue(reason(client().health()).contains("something else"))

        respond(200, "<html>Vite dev server</html>")
        assertTrue(reason(client().health()).contains("something else"))

        respond(404, "Cannot GET /health")
        assertTrue(reason(client().health()).contains("HTTP 404"))
    }

    @Test
    fun `breadcrumb answering with a failure says so`() = runBlocking {
        respond(503, """{"service":"breadcrumb","status":"degraded"}""")

        assertTrue(reason(client().health()).contains("Breadcrumb answered, but is not ok (HTTP 503)"))
    }

    @Test
    fun `nothing listening points at adb reverse`() = runBlocking {
        val client = client()
        server.close()

        assertTrue(reason(client.health()).contains("adb reverse"))
    }

    /**
     * What adb reverse does when nothing listens on the dev machine: the
     * phone-side connection is accepted, then closed without a byte of reply.
     */
    private fun acceptThenClose(readRequestFirst: Boolean): String = runBlocking {
        ServerSocket(0).use { socket ->
            val acceptor = thread {
                // a few times over: OkHttp retries a connection that fails like this
                repeat(3) {
                    runCatching {
                        socket.accept().use { conn ->
                            // Closing with the request unread resets the connection;
                            // reading it first closes cleanly, an end of stream.
                            if (readRequestFirst) conn.getInputStream().read(ByteArray(4096))
                        }
                    }
                }
            }
            val client = ServerClient("http://127.0.0.1:${socket.localPort}", healthTimeoutMillis = 2_000)

            val reason = reason(client.health())

            socket.close()
            acceptor.join()
            reason
        }
    }

    @Test
    fun `a forwarded port with no server behind it points at the server`() {
        val reset = acceptThenClose(readRequestFirst = false)
        assertTrue("got: $reset", reset.contains("is the server running"))

        val endOfStream = acceptThenClose(readRequestFirst = true)
        assertTrue("got: $endOfStream", endOfStream.contains("is the server running"))
    }

    @Test
    fun `an uploaded memory carries everything the server indexes, and no more`() = runBlocking {
        respond(200, """{"results":[{"id":"m1","enriched":true,"embedded":true}]}""")

        client().upload(listOf(
            Memory(
                id = "m1",
                type = MemoryType.IMAGE,
                hasLink = true,
                capturedAt = 1_700_000_000_000,
                contentCreatedAt = 1_690_000_000_000,
                sourceApp = "com.whatsapp",
                sourceAppLabel = "WhatsApp",
                rawText = "have a look https://example.com",
                extractedText = "Qualcomm SWE Internship",
                title = "Internship posting",
                localUri = "file:///data/originals/m1.jpg",
                syncState = SyncState.PENDING,
            )
        ))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/memories", request.url.encodedPath)
        val body = request.body?.utf8().orEmpty()
        // the original stays on the phone (architecture rule 1)
        assertFalse("payload leaked localUri: $body", body.contains("localUri"))
        assertFalse("payload leaked syncState: $body", body.contains("syncState"))
        for (field in listOf("m1", "IMAGE", "WhatsApp", "Qualcomm SWE Internship", "1700000000000")) {
            assertTrue("payload is missing $field: $body", body.contains(field))
        }
    }

    @Test
    fun `a batch goes in one request and is answered memory by memory`() = runBlocking {
        respond(
            200,
            """{"results":[
                {"id":"a","enriched":true,"embedded":true},
                {"id":"b","enriched":false,"embedded":true,"retryable":true,"reason":"429 rate limited"}
            ]}""",
        )

        val results = client().upload(listOf(memory("a"), memory("b")))

        assertEquals(1, server.requestCount)
        assertEquals(UploadResult.Stored(remoteId = "a", enriched = true, embedded = true), results["a"])
        // stored, but its model call is still owed: the phone must send it again
        assertTrue(results["b"] is UploadResult.Unavailable)
    }

    @Test
    fun `a memory the server never mentions is not treated as sent`() = runBlocking {
        respond(200, """{"results":[{"id":"a","enriched":true,"embedded":true}]}""")

        val results = client().upload(listOf(memory("a"), memory("missing")))

        assertTrue(results["missing"] is UploadResult.Unavailable)
    }

    @Test
    fun `a refusal is told apart from the server being unavailable`() = runBlocking {
        // 4xx: our payload is wrong, and re-sending it changes nothing
        respond(400, """{"error":"invalid memory","invalid":[{"index":0,"errors":["type must be one of TEXT, LINK"]}]}""")
        assertTrue(client().upload(listOf(memory("m1")))["m1"] is UploadResult.Rejected)

        // 5xx and the two "ask again" 4xx codes: worth retrying
        respond(503, "")
        assertTrue(client().upload(listOf(memory("m1")))["m1"] is UploadResult.Unavailable)
        respond(429, "")
        assertTrue(client().upload(listOf(memory("m1")))["m1"] is UploadResult.Unavailable)
    }

    @Test
    fun `an upload with nowhere to go is retryable, never a refusal`() = runBlocking {
        val client = client()
        server.close()

        val result = client.upload(listOf(memory("m1")))["m1"]

        assertTrue("offline must be retryable, got $result", result is UploadResult.Unavailable)
        assertTrue((result as UploadResult.Unavailable).reason.contains("adb reverse"))
    }

    @Test
    fun `a success the app cannot parse leaves the memory queued`() = runBlocking {
        // no per-memory answer to trust: better to send again than to assume
        respond(200, "OK")

        assertTrue(client().upload(listOf(memory("m1")))["m1"] is UploadResult.Unavailable)
    }

    // --- search (steps 4.1-4.4); the shape is server/src/search.ts's SearchAnswer ---

    @Test
    fun `a search sends the phrase as typed and keeps the server's ranking`() = runBlocking {
        respond(
            200,
            """{"query":"that internship screenshot from april",
                "interpretation":{"query":"internship","types":["IMAGE"],"from":"2026-04-01","to":"2026-04-30","sourceApp":null},
                "results":[
                  {"id":"b","score":0.0325,"ranks":{"vector":2,"text":1},"type":"IMAGE","hasLink":false,
                   "capturedAt":"2026-09-10T10:00:00.000Z","summary":"Samsung internship screenshot","readText":null},
                  {"id":"a","score":0.0164,"ranks":{"vector":1,"text":null},"type":"TEXT","summary":null}
                ]}""",
        )

        val outcome = client().search("that internship screenshot from april", limit = 20)

        val request = server.takeRequest()
        assertEquals("/search", request.url.encodedPath)
        // taking the phrase apart is the server's job (4.3), so it goes whole
        assertEquals("that internship screenshot from april", request.url.queryParameter("q"))
        assertEquals("20", request.url.queryParameter("limit"))

        assertTrue("expected Found, got $outcome", outcome is SearchOutcome.Found)
        val found = outcome as SearchOutcome.Found
        assertEquals(listOf("b", "a"), found.hits.map { it.id })
        assertEquals(Ranks(vector = 2, text = 1), found.hits[0].ranks)
        assertEquals(Ranks(vector = 1, text = null), found.hits[1].ranks)
        assertEquals("Samsung internship screenshot", found.hits[0].summary)
        assertEquals(Interpretation("internship", listOf("IMAGE"), "2026-04-01", "2026-04-30", null), found.interpretation)
    }

    @Test
    fun `a phrase the server could not take apart still brings its results`() = runBlocking {
        respond(
            200,
            """{"query":"qualcomm","interpretation":null,"interpretationError":"503 UNAVAILABLE",
                "results":[{"id":"a","score":0.03,"ranks":{"vector":1,"text":1}}]}""",
        )

        val found = client().search("qualcomm") as SearchOutcome.Found

        assertEquals(null, found.interpretation)
        assertEquals(listOf("a"), found.hits.map { it.id })
    }

    @Test
    fun `an all-filter listing arrives unscored`() = runBlocking {
        respond(200, """{"query":"links from whatsapp","results":[{"id":"a","score":null,"ranks":{"vector":null,"text":null}}]}""")

        val found = client().search("links from whatsapp") as SearchOutcome.Found

        assertEquals(null, found.hits.single().score)
    }

    @Test
    fun `a server that cannot search is unavailable, and not offline`() = runBlocking {
        // 503: the embedding model is busy; 502: it refused
        respond(503, """{"error":"could not read the search","reason":"429 RESOURCE_EXHAUSTED","retryable":true}""")
        val busy = client().search("x")
        assertEquals(false, (busy as SearchOutcome.Unavailable).offline)

        respond(502, """{"error":"could not read the search","reason":"400 API key not valid"}""")
        assertEquals(false, (client().search("x") as SearchOutcome.Unavailable).offline)
    }

    @Test
    fun `an answer the app cannot read is unavailable rather than empty`() = runBlocking {
        // "found nothing" would be a lie; the phone's own word search should stand
        respond(200, "OK")

        assertTrue(client().search("x") is SearchOutcome.Unavailable)
    }

    @Test
    fun `no server at all is offline`() = runBlocking {
        val client = client()
        server.close()

        val outcome = client.search("x") as SearchOutcome.Unavailable

        assertTrue(outcome.offline)
        assertTrue(outcome.reason.contains("adb reverse"))
    }

    @Test
    fun `a server that never answers times out`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .body("""{"service":"breadcrumb","status":"ok"}""")
                .headersDelay(5, TimeUnit.SECONDS)
                .build()
        )

        assertEquals("timed out", reason(client(timeoutMillis = 200).health()))
    }
}
