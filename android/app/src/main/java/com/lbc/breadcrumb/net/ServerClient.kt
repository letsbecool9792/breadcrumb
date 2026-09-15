package com.lbc.breadcrumb.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.util.concurrent.TimeUnit

/** What the app knows about reaching its backend. */
sealed interface ServerStatus {
    data object Checking : ServerStatus
    data object Reachable : ServerStatus

    /** @param reason short and specific enough to act on while developing. */
    data class Unreachable(val reason: String) : ServerStatus
}

/**
 * The app's only way to the backend. Endpoints arrive one per step: health
 * now, ingest at 3.3, search at 4.1. Hand-written rather than generated -- one
 * server, one client, two or three endpoints.
 */
class ServerClient(
    private val baseUrl: String,
    http: OkHttpClient = OkHttpClient(),
    /**
     * Short on purpose. The server is either on the other end of a USB cable
     * or not there at all, and a screen that says "checking…" for OkHttp's
     * default 10s after launch looks broken.
     */
    healthTimeoutMillis: Long = 5_000,
) {

    private val healthHttp = http.newBuilder()
        .callTimeout(healthTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    suspend fun health(): ServerStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/health").build()
        try {
            healthHttp.newCall(request).execute().use { response ->
                interpretHealth(response.code, response.body.string())
            }
        } catch (e: IOException) {
            ServerStatus.Unreachable(describe(e))
        }
    }
}

@Serializable
private data class HealthBody(val service: String? = null, val status: String? = null)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Anything that answers on the port is not necessarily Breadcrumb: another dev
 * server on 3000 would answer too. So the body must name the service.
 */
internal fun interpretHealth(code: Int, body: String): ServerStatus {
    val health = runCatching { json.decodeFromString<HealthBody>(body) }.getOrNull()
    val isBreadcrumb = health?.service == "breadcrumb"
    return when {
        isBreadcrumb && code == 200 && health.status == "ok" -> ServerStatus.Reachable
        isBreadcrumb -> ServerStatus.Unreachable("Breadcrumb answered, but is not ok (HTTP $code)")
        else -> ServerStatus.Unreachable("something else answered on the port (HTTP $code)")
    }
}

/**
 * The two ways the dev setup fails look different on the wire, so each gets
 * a hint that says which half is missing.
 */
internal fun describe(e: IOException): String = when {
    // Nothing listening on the phone's port: adb reverse is not set, and it is
    // lost whenever the phone reconnects. (Checked before SocketException,
    // which ConnectException extends.)
    e is ConnectException -> "connection refused -- run adb reverse tcp:3000 tcp:3000"
    e is InterruptedIOException -> "timed out"
    // Accepted, then closed before any reply: what adb reverse does when
    // nothing listens on the dev machine. Arrives as a reset or as an end of
    // stream depending on timing and platform, so match both.
    e is SocketException || e is EOFException || e.cause is EOFException ->
        "connection closed without a reply -- is the server running? (npm run dev)"
    else -> e.message ?: e.javaClass.simpleName
}
