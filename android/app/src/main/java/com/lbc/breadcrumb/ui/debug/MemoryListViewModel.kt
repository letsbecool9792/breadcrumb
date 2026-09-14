package com.lbc.breadcrumb.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.Memory
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

    val memories: StateFlow<List<Memory>> = dao.observeAll()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun addSample() = viewModelScope.launch {
        dao.upsert(randomSampleMemory())
    }

    fun delete(memory: Memory) = viewModelScope.launch {
        dao.delete(memory)
    }

    fun clearAll() = viewModelScope.launch {
        dao.clear()
    }
}
