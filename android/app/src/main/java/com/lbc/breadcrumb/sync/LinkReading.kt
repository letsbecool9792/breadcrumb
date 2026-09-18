package com.lbc.breadcrumb.sync

import android.util.Log
import com.lbc.breadcrumb.capture.UrlText
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryDao
import com.lbc.breadcrumb.net.PageReading
import com.lbc.breadcrumb.net.PageRules
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Reads the pages of saved links, so a link copied bare from a chat is found
 * by what the page was about -- its title, its description -- and not only by
 * the words in its URL.
 *
 * Runs in the upload worker, ahead of the uploads: the worker only runs with a
 * network, and a link read first goes up with its page in one send. Every
 * unread link is read -- links saved before this existed too -- and one
 * already on the server goes again with what its page said.
 *
 * Every answer is final but one. A page that errors, is not a page, or stands
 * behind a login is recorded as read with nothing found, and never fetched
 * again. A page that cannot be reached is final too while the phone is
 * online -- a dead link stays dead -- but not when the phone has lost its
 * network: then the pass stops, and the links wait for the next one.
 */
class LinkReading(
    private val dao: MemoryDao,
    private val read: suspend (url: String) -> PageReading,
    /** Whether the phone has a working connection right now. */
    private val isOnline: () -> Boolean,
    /** Pages fetched at once: a backlog of imported bookmarks should not take a page at a time. */
    private val parallel: Int = 4,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** @return how many links came back with something read from their page. */
    suspend fun readAll(): Int {
        var found = 0
        while (true) {
            val batch = dao.unreadLinks(limit = parallel)
            if (batch.isEmpty()) return found
            val outcomes = coroutineScope { batch.map { async { readOne(it) } }.awaitAll() }
            found += outcomes.count { it == Outcome.FOUND }
            if (Outcome.OFFLINE in outcomes) return found
        }
    }

    private enum class Outcome { FOUND, NOTHING, OFFLINE }

    private suspend fun readOne(memory: Memory): Outcome {
        val url = UrlText.firstUrl(memory.rawText)?.let(PageRules::secure)
        if (url == null || !PageRules.worthReading(url)) {
            dao.setPageReading(memory.id, title = null, text = "", now = clock())
            return Outcome.NOTHING
        }
        return when (val reading = read(url)) {
            is PageReading.Read -> {
                dao.setPageReading(memory.id, reading.meta.title, reading.meta.readText(), clock())
                Outcome.FOUND
            }
            is PageReading.Unreadable -> {
                Log.i(TAG, "nothing to read at ${memory.id}'s page: ${reading.reason}")
                dao.setPageReading(memory.id, title = null, text = "", now = clock())
                Outcome.NOTHING
            }
            is PageReading.Unreached -> {
                if (!isOnline()) return Outcome.OFFLINE
                Log.i(TAG, "${memory.id}'s page could not be reached: ${reading.reason}")
                dao.setPageReading(memory.id, title = null, text = "", now = clock())
                Outcome.NOTHING
            }
        }
    }

    private companion object {
        const val TAG = "LinkReading"
    }
}
