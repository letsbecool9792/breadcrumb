package com.lbc.breadcrumb.net

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
