package com.lbc.breadcrumb.ui.home

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lbc.breadcrumb.BreadcrumbApp
import com.lbc.breadcrumb.capture.WrittenCapture
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.FtsQuery
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.OriginalStore
import com.lbc.breadcrumb.data.SyncState
import com.lbc.breadcrumb.net.Interpretation
import com.lbc.breadcrumb.net.SearchHit
import com.lbc.breadcrumb.net.SearchOutcome
import com.lbc.breadcrumb.sync.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A result on screen: the phone's own row, and what the server said about it when it ranked it. */
data class Result(val memory: Memory, val hit: SearchHit?)

/** A file picked in the app's own sheet, not yet kept. */
data class Attachment(val uri: Uri, val name: String?, val isPdf: Boolean)

/** Where a search has got to, which is what the line over the results says. */
enum class SearchStatus {
    /** Showing the phone's own word matches while the ranked answer is on its way. */
    RANKING,
    RANKED,
    /** The server could not be reached; the phone's word matches stand. */
    OFFLINE,
    /** The server answered but could not search -- a busy model, say. */
    UNAVAILABLE,
}

sealed interface SearchState {
    /** No search: the mosaic. */
    data object Resting : SearchState

    data class Searching(
        val phrase: String,
        val results: List<Result>,
        /** How the server read the phrase (4.3); null until it answers, or when it could not. */
        val interpretation: Interpretation?,
        val status: SearchStatus,
    ) : SearchState
}

/**
 * The search screen (steps 4.2 and 4.5): everything kept, a search over it,
 * and one memory opened.
 *
 * A search shows two answers in turn. The phone's own word index (step 2.2)
 * answers at once and offline; the server's ranked search (4.1-4.4) answers a
 * moment later and replaces it. So typing always shows something, and a
 * search still works -- by words alone -- when the server does not.
 */
class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BreadcrumbDatabase.get(app).memoryDao()
    private val server = (app as BreadcrumbApp).server

    /** Everything kept, newest first. Null until Room first answers, so launch never flashes an empty archive. */
    val memories: StateFlow<List<Memory>?> = dao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Saves the server has not seen yet, which ranked search cannot find. */
    val unsent: StateFlow<Int> = dao.observeUnsyncedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Compose state rather than a flow: a text field fed from a flow can drop keystrokes. */
    var query by mutableStateOf("")
        private set

    var search by mutableStateOf<SearchState>(SearchState.Resting)
        private set

    /** The memory shown in detail (4.5), if any. */
    var opened by mutableStateOf<Result?>(null)
        private set

    /** A memory just deleted, while it can still be brought back (4.6). */
    var removed by mutableStateOf<Memory?>(null)
        private set

    /** The app's own sheet for keeping something (the "+"), open or not. */
    var writing by mutableStateOf(false)
        private set

    /**
     * What is in that sheet. Kept when it is swiped away, so a thought half
     * written is there when it opens again; emptied only once it is kept.
     */
    var draft by mutableStateOf("")
        private set
    val attachments = mutableStateListOf<Attachment>()

    var keeping by mutableStateOf(false)
        private set

    /** The last keep saved nothing -- every picked file failed to copy, say. */
    var keepFailed by mutableStateOf(false)
        private set

    private val store = OriginalStore(app)
    private val appScope = (app as BreadcrumbApp).applicationScope
    private var finishing: Job? = null

    /** Deletes and restores run in the order they were asked for, whatever the threads do. */
    private val writes = Mutex()

    init {
        // Catch-up: anything left queued by an earlier run, or saved offline,
        // goes out as soon as there is a network.
        UploadWorker.schedule(app)

        viewModelScope.launch {
            snapshotFlow { normalize(query) }
                .distinctUntilChanged()
                // a new keystroke abandons the search in flight, server call included
                .collectLatest(::run)
        }
    }

    fun onQueryChange(text: String) {
        query = text
    }

    fun clear() {
        query = ""
    }

    fun open(result: Result) {
        opened = result
    }

    fun close() {
        opened = null
    }

    fun startWriting() {
        keepFailed = false
        writing = true
    }

    /** Closes the sheet and keeps the draft. */
    fun stopWriting() {
        writing = false
    }

    fun onDraftChange(text: String) {
        draft = text
    }

    /** Files picked in the sheet, named for their tiles. A file picked twice is there once. */
    fun attach(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        viewModelScope.launch {
            val picked = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val type = runCatching { resolver.getType(uri) }.getOrNull()
                    val name = runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                        }
                    }.getOrNull()
                    Attachment(uri, name, isPdf = type == "application/pdf")
                }
            }
            picked.filter { new -> attachments.none { it.uri == new.uri } }.let(attachments::addAll)
        }
    }

    fun detach(attachment: Attachment) {
        attachments.remove(attachment)
    }

    /**
     * Keeps what the sheet holds. On the app's scope: copying picked files
     * takes a moment, and leaving the screen must not cut a save short.
     */
    fun keep() {
        val text = draft.trim()
        val files = attachments.toList()
        if (keeping || (text.isEmpty() && files.isEmpty())) return
        keeping = true
        keepFailed = false
        val app = getApplication<Application>()
        appScope.launch {
            val saved = WrittenCapture(app, dao, store).save(text, files.map { it.uri })
            withContext(Dispatchers.Main) {
                keeping = false
                if (saved.isEmpty()) {
                    keepFailed = true
                } else {
                    draft = ""
                    attachments.clear()
                    writing = false
                    UploadWorker.schedule(app)
                }
            }
        }
    }

    /** One memory, live -- an open detail shows its summary the moment it is copied back. */
    fun observe(id: String): Flow<Memory?> = dao.observeById(id)

    /**
     * Writes the person's note on a memory, and sends it again so the server
     * searches by the note too. On the app's scope: the sheet closing is one
     * of the ways a note gets written, and must not cut the write short.
     */
    fun setNote(id: String, note: String?) {
        val text = note?.trim()?.takeIf { it.isNotEmpty() }
        appScope.launch {
            if (dao.setNote(id, text, System.currentTimeMillis()) > 0) UploadWorker.schedule(getApplication())
        }
    }

    /**
     * Deletes a memory at once, with a few seconds to take it back.
     *
     * The row goes now, with the delete remembered for the server, so it
     * leaves the mosaic and the results immediately. The original file stays
     * until the undo has passed, so undo brings back everything.
     */
    fun delete(memory: Memory) {
        opened = null
        // a second delete makes the first one final
        finishing?.cancel()
        removed?.let(::finish)

        removed = memory
        (search as? SearchState.Searching)?.let { shown ->
            search = shown.copy(results = shown.results.filterNot { it.memory.id == memory.id })
        }
        viewModelScope.launch(Dispatchers.IO) {
            writes.withLock { dao.deleteEverywhere(memory, System.currentTimeMillis()) }
        }
        finishing = viewModelScope.launch {
            delay(UNDO_MS)
            removed = null
            finish(memory)
        }
    }

    /**
     * Brings back the memory just deleted. As PENDING: the server may already
     * have deleted it, and sending it again costs nothing if it has not.
     */
    fun undo() {
        val memory = removed ?: return
        finishing?.cancel()
        removed = null
        viewModelScope.launch(Dispatchers.IO) {
            writes.withLock { dao.restore(memory.copy(syncState = SyncState.PENDING)) }
            UploadWorker.schedule(getApplication())
        }
    }

    /**
     * Past undoing: the original file goes, and the delete goes to the
     * server. On the app's scope, so leaving the screen cannot cut it short.
     */
    private fun finish(memory: Memory) {
        appScope.launch {
            store.delete(memory)
            UploadWorker.schedule(getApplication())
        }
    }

    override fun onCleared() {
        // leaving mid-undo makes the delete final
        removed?.let(::finish)
        super.onCleared()
    }

    private suspend fun run(phrase: String) {
        if (phrase.isEmpty()) {
            search = SearchState.Resting
            return
        }

        val local = localMatches(phrase)
        search = SearchState.Searching(phrase, local, interpretation = null, SearchStatus.RANKING)

        // Wait for a pause in the typing: each ranked search is a model call
        // on a free tier, and "q", "qu", "qua" are not searches anyone meant.
        delay(PAUSE_MS)

        search = when (val outcome = server.search(phrase, LIMIT)) {
            is SearchOutcome.Found ->
                SearchState.Searching(phrase, ranked(outcome.hits), outcome.interpretation, SearchStatus.RANKED)
            is SearchOutcome.Unavailable -> {
                Log.w(TAG, "ranked search unavailable, showing word matches: ${outcome.reason}")
                val status = if (outcome.offline) SearchStatus.OFFLINE else SearchStatus.UNAVAILABLE
                SearchState.Searching(phrase, local, interpretation = null, status)
            }
        }
    }

    private suspend fun localMatches(phrase: String): List<Result> {
        val match = FtsQuery.matchExpression(phrase) ?: return emptyList()
        return dao.searchOnce(match, LIMIT).map { Result(it, hit = null) }
    }

    private suspend fun ranked(hits: List<SearchHit>): List<Result> =
        join(hits, dao.getByIds(hits.map { it.id }).associateBy { it.id })

    companion object {
        private const val TAG = "Home"

        /** Long enough to span a word being typed, short enough to feel like search-as-you-type. */
        const val PAUSE_MS = 450L

        /** How long a delete can be taken back. */
        const val UNDO_MS = 5_000L

        const val LIMIT = 30

        /** "  qualcomm   intern " and "qualcomm intern" are one search. */
        fun normalize(text: String): String = text.trim().replace(Regex("""\s+"""), " ")

        /**
         * The server's order, the phone's rows. A hit the phone does not hold
         * is a memory deleted here that the server still has -- deletes do not
         * sync -- and is dropped rather than shown as something that cannot open.
         */
        fun join(hits: List<SearchHit>, held: Map<String, Memory>): List<Result> =
            hits.mapNotNull { hit -> held[hit.id]?.let { Result(it, hit) } }
    }
}
