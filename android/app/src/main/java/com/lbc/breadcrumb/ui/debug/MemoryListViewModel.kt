package com.lbc.breadcrumb.ui.debug

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lbc.breadcrumb.BreadcrumbApp
import com.lbc.breadcrumb.BuildConfig
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.FtsQuery
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.OriginalStore
import com.lbc.breadcrumb.net.ServerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs the temporary debug list. Talks to the DAO directly on purpose -- a
 * repository layer only earns its place at step 3.5, when there is a second
 * data source to reconcile against.
 */
class MemoryListViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = BreadcrumbDatabase.get(app).memoryDao()
    private val store = OriginalStore(app)

    /**
     * Compose state rather than a StateFlow: a text field fed from a flow can
     * drop keystrokes or jump the cursor while typing.
     */
    var query by mutableStateOf("")
        private set

    /** Everything, or what matches [query] once it holds a word. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val memories: StateFlow<List<Memory>> = snapshotFlow { query }
        .map(FtsQuery::matchExpression)
        // "qualcomm" and "qualcomm " are the same search; do not requery
        .distinctUntilChanged()
        .flatMapLatest { match -> if (match == null) dao.observeAll() else dao.search(match) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun onQueryChange(text: String) {
        query = text
    }

    private val server = (app as BreadcrumbApp).server
    private var serverCheck: Job? = null

    var serverStatus by mutableStateOf<ServerStatus>(ServerStatus.Checking)
        private set

    init {
        checkServer()
    }

    /** On launch, and again on tap -- adb reverse drops whenever the phone reconnects. */
    fun checkServer() {
        if (serverCheck?.isActive == true) return
        serverCheck = viewModelScope.launch {
            serverStatus = ServerStatus.Checking
            serverStatus = server.health()
            Log.i(TAG, "server ${BuildConfig.SERVER_URL}: $serverStatus")
        }
    }

    fun addSample() = viewModelScope.launch {
        dao.upsert(randomSampleMemory())
    }

    /**
     * Row first, then file: the list should never show a memory whose original
     * has already gone. If the file delete fails the leftover is an orphan on
     * disk, which is harmless; the reverse would not be.
     */
    fun delete(memory: Memory) = viewModelScope.launch(Dispatchers.IO) {
        dao.delete(memory)
        store.delete(memory)
    }

    fun clearAll() = viewModelScope.launch(Dispatchers.IO) {
        dao.clear()
        store.clear()
    }

    private companion object {
        const val TAG = "MemoryList"
    }
}
