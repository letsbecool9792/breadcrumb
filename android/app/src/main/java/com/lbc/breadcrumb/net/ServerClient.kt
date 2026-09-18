package com.lbc.breadcrumb.net

import com.lbc.breadcrumb.data.Memory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** What the app knows about reaching its backend. */
sealed interface ServerStatus {
    data object Checking : ServerStatus
    data object Reachable : ServerStatus

    /** @param reason short and specific enough to act on while developing. */
    data class Unreachable(val reason: String) : ServerStatus
}

/** What became of one memory we tried to send. */
sealed interface UploadResult {
    /** Stored. [remoteId] is the server's id for it, which is the phone's own. */
    data class Stored(val remoteId: String, val enriched: Boolean, val embedded: Boolean) : UploadResult

    /** The server refused it. Sending the same bytes again gets the same answer. */
    data class Rejected(val reason: String) : UploadResult

    /** Offline, timed out, or the server is having a bad time. Worth another go. */
    data class Unavailable(val reason: String) : UploadResult
}

/** Implemented by [ServerClient]; an interface so the queue can be tested without a server. */
interface MemoryUploadApi {
    /**
     * Sends several memories in one request, and answers for each by id.
     *
     * A batch, because ingest runs a model call per request rather than per
     * memory, and the free tier counts requests. An id missing from the result
     * is treated as unsent.
     */
    suspend fun upload(memories: List<Memory>): Map<String, UploadResult>

    /**
     * Sends the pictures themselves, for memories already stored on the server
     * (step 3.6). They are read and dropped there; nothing of them is kept but
     * what the model saw (architecture rule 1).
     */
    suspend fun uploadImages(images: List<OutgoingImage>): Map<String, UploadResult>
}

/** One picture on its way to be read: the memory it belongs to, and its bytes. */
data class OutgoingImage(val memoryId: String, val mimeType: String, val bytes: ByteArray) {
    // data class equality on a ByteArray compares references, which would make
    // any test of these quietly wrong
    override fun equals(other: Any?): Boolean =
        this === other || (other is OutgoingImage && memoryId == other.memoryId &&
            mimeType == other.mimeType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * (31 * memoryId.hashCode() + mimeType.hashCode()) + bytes.contentHashCode()
}

/**
 * The app's only way to the backend. Endpoints arrive one per step: health,
 * ingest, and search at 4.1. Hand-written rather than generated -- one server,
 * one client, a handful of endpoints.
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
    /** Ingest runs two model calls server-side; measured around 13s per memory. */
    uploadTimeoutMillis: Long = 90_000,
) : MemoryUploadApi {

    private val healthHttp = http.newBuilder()
        .callTimeout(healthTimeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    private val uploadHttp = http.newBuilder()
        .callTimeout(uploadTimeoutMillis, TimeUnit.MILLISECONDS)
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

    /**
     * Sends one memory. Idempotent on the memory's own id, so a retry after a
     * half-finished upload replaces rather than duplicates.
     *
     * Ingest runs two model calls server-side and takes seconds; the timeout
     * allows for that. Nobody is waiting -- the user saw "Saved" long ago
     * (architecture rule 2).
     */
    override suspend fun upload(memories: List<Memory>): Map<String, UploadResult> = withContext(Dispatchers.IO) {
        if (memories.isEmpty()) return@withContext emptyMap()

        val payload = json.encodeToString(memories.map(MemoryPayload::of))
        val request = Request.Builder()
            .url("$baseUrl/memories")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            uploadHttp.newCall(request).execute().use { response ->
                val body = response.body.string()
                // The server answers per memory, and a 503 still carries those
                // answers: some may be stored, others waiting on a model call
                // that can be tried later.
                val results = runCatching { json.decodeFromString<IngestReplies>(body).results }.getOrNull()
                when {
                    results != null -> memories.associate { it.id to (results.forId(it.id) ?: notAnswered) }
                    // 4xx: our fault and unchanged by repetition -- except 408
                    // and 429, which are the server asking for more time.
                    response.code in 400..499 && response.code != 408 && response.code != 429 ->
                        memories.associate {
                            it.id to UploadResult.Rejected("HTTP ${response.code}: ${body.take(200)}")
                        }
                    else -> memories.associate { it.id to UploadResult.Unavailable("HTTP ${response.code}") }
                }
            }
        } catch (e: IOException) {
            memories.associate { it.id to UploadResult.Unavailable(describe(e)) }
        }
    }

    override suspend fun uploadImages(images: List<OutgoingImage>): Map<String, UploadResult> =
        withContext(Dispatchers.IO) {
            if (images.isEmpty()) return@withContext emptyMap()

            val payload = json.encodeToString(
                images.map {
                    ImagePayload(
                        id = it.memoryId,
                        mimeType = it.mimeType,
                        data = Base64.getEncoder().encodeToString(it.bytes),
                    )
                }
            )
            val request = Request.Builder()
                .url("$baseUrl/memories/images")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            try {
                uploadHttp.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    val results = runCatching { json.decodeFromString<IngestReplies>(body).results }.getOrNull()
                    when {
                        results != null ->
                            images.associate { it.memoryId to (results.forId(it.memoryId) ?: notAnswered) }
                        response.code in 400..499 && response.code != 408 && response.code != 429 ->
                            images.associate {
                                it.memoryId to UploadResult.Rejected("HTTP ${response.code}: ${body.take(200)}")
                            }
                        else -> images.associate { it.memoryId to UploadResult.Unavailable("HTTP ${response.code}") }
                    }
                }
            } catch (e: IOException) {
                images.associate { it.memoryId to UploadResult.Unavailable(describe(e)) }
            }
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * The wire shape of a memory. Hand-written to match the server (CLAUDE.md: no
 * codegen between the two), and deliberately without `localUri` or
 * `syncState`: the original never leaves the device (rule 1), and the sync
 * state is the phone's own business.
 */
@Serializable
private data class MemoryPayload(
    val id: String,
    val type: String,
    val hasLink: Boolean,
    val capturedAt: Long,
    val updatedAt: Long,
    val contentCreatedAt: Long? = null,
    val sourceApp: String? = null,
    val sourceAppLabel: String? = null,
    val title: String? = null,
    val rawText: String? = null,
    val extractedText: String? = null,
) {
    companion object {
        fun of(memory: Memory) = MemoryPayload(
            id = memory.id,
            type = memory.type.name,
            hasLink = memory.hasLink,
            capturedAt = memory.capturedAt,
            updatedAt = memory.updatedAt,
            contentCreatedAt = memory.contentCreatedAt,
            sourceApp = memory.sourceApp,
            sourceAppLabel = memory.sourceAppLabel,
            title = memory.title,
            rawText = memory.rawText,
            extractedText = memory.extractedText,
        )
    }
}

/** No default for [results]: a body without it -- an error page, a refusal -- must not decode into "no answers". */
/** base64 rather than multipart: no parser to add on either side, and the model wants it this way. */
@Serializable
private data class ImagePayload(val id: String, val mimeType: String, val data: String)

/** No default for [results]: a body without it -- an error page, a refusal -- must not decode into "no answers". */
@Serializable
private data class IngestReplies(val results: List<IngestReply>)

@Serializable
private data class IngestReply(
    val id: String? = null,
    val enriched: Boolean = false,
    val embedded: Boolean = false,
    /** The memory is stored, but a model call failed in a way that may pass. */
    val retryable: Boolean = false,
    val reason: String? = null,
)

/** A memory the server did not mention is one we cannot call sent. */
private val notAnswered = UploadResult.Unavailable("the server did not answer for this memory")

private fun List<IngestReply>.forId(id: String): UploadResult? {
    val reply = firstOrNull { it.id == id } ?: return null
    return if (reply.retryable) {
        UploadResult.Unavailable(reply.reason ?: "the server asked us to send it again")
    } else {
        UploadResult.Stored(remoteId = reply.id ?: id, enriched = reply.enriched, embedded = reply.embedded)
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
