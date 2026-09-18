package com.lbc.breadcrumb.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** What became of reading one link's page. */
sealed interface PageReading {
    data class Read(val meta: PageMeta) : PageReading

    /** The page answered, and there is nothing to be had from it: an error, not a page, a wall. Final. */
    data class Unreadable(val reason: String) : PageReading

    /** It could not be reached -- which may be the network rather than the page. */
    data class Unreached(val reason: String) : PageReading
}

/**
 * Fetches a link's page from the phone, as a browser would, and reads its
 * head (see [PageMeta]).
 *
 * From the phone rather than the server: it is the person's own connection,
 * as when they open the link, and a residential connection is refused by
 * fewer sites than a server's. The server never fetches arbitrary URLs.
 *
 * Only the head is wanted, so the body is read until `</head>` or
 * [maxBytes], whichever comes first -- never a whole page, never an image.
 */
class PageReader(
    http: OkHttpClient = OkHttpClient(),
    timeoutMillis: Long = 12_000,
    private val maxBytes: Long = 512 * 1024,
    /**
     * Says what is asking. Browser-shaped at the front, as nearly every link
     * previewer's is: plenty of sites serve an empty page to anything else.
     */
    private val userAgent: String = "Mozilla/5.0 (Linux; Android) Breadcrumb/1.0 (link preview)",
) {
    private val client = http.newBuilder()
        .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun read(url: String): PageReading = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.1")
                .header("Accept-Language", "${Locale.getDefault().toLanguageTag()},en;q=0.8")
                .build()
        } catch (_: IllegalArgumentException) {
            return@withContext PageReading.Unreadable("not a URL")
        }

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use PageReading.Unreadable("HTTP ${response.code}")
                val type = response.body.contentType()
                if (type != null && type.subtype != "html" && type.subtype != "xhtml+xml") {
                    return@use PageReading.Unreadable("not a page: $type")
                }
                val meta = PageMeta.parse(head(response.body))
                if (meta.isEmpty) PageReading.Unreadable("nothing in its head") else PageReading.Read(meta)
            }
        } catch (e: IOException) {
            PageReading.Unreached(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The body up to the end of its head, or [maxBytes] of it. */
    private fun head(body: ResponseBody): String {
        val source = body.source()
        val buffer = Buffer()
        while (buffer.size < maxBytes) {
            if (source.read(buffer, 8_192) == -1L) break
            if (buffer.indexOf(HEAD_END) >= 0 || buffer.indexOf(HEAD_END_UPPER) >= 0) break
        }
        val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return buffer.readString(minOf(buffer.size, maxBytes), charset)
    }

    private companion object {
        val HEAD_END = "</head>".encodeUtf8()
        val HEAD_END_UPPER = "</HEAD>".encodeUtf8()
    }
}
