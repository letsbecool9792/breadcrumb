package com.lbc.breadcrumb.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.OriginalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    val memories: StateFlow<List<Memory>> = dao.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

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
}
