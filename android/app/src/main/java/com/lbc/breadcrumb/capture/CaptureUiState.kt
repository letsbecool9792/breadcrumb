package com.lbc.breadcrumb.capture

import androidx.annotation.StringRes
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType

sealed interface CaptureUiState {

    /** Still copying or reading. The sheet cannot be dismissed yet. */
    data object Working : CaptureUiState

    data class Saved(val memories: List<Memory>, val attempted: Int) : CaptureUiState

    /** Nothing was saved; [message] says why. */
    data class Failed(@param:StringRes val message: Int) : CaptureUiState

    data object Undone : CaptureUiState
}

/** How the sheet shows what was kept. One shape per kind of save. */
sealed interface CapturePreview {

    /** A single photo, shown large -- a photo is verified by looking at it. */
    data class Photo(val memory: Memory, val caption: String?) : CapturePreview

    /** Several files at once, as a row of tiles. */
    data class Files(val memories: List<Memory>) : CapturePreview {
        val visible: List<Memory> get() = memories.take(MAX_TILES)

        /** How many files the tiles do not show; drawn as "+N" on the last tile. */
        val overflow: Int get() = (memories.size - MAX_TILES).coerceAtLeast(0)
    }

    /** A PDF. [title] is null only when neither a subject nor a filename existed. */
    data class Document(val memory: Memory, val title: String?, val caption: String?) : CapturePreview

    data class Link(val host: String?, val headline: String, val detail: String?) : CapturePreview

    data class Note(val text: String) : CapturePreview
}

private const val MAX_TILES = 3

/**
 * The wording and shape of the capture sheet. Pure, so it is tested on the JVM;
 * the composables only lay it out.
 */
object CaptureSummary {

    /**
     * The mono line under the title, e.g. "link · from chrome". Lowercase
     * throughout: it is chrome, and chrome recedes.
     */
    fun subtitle(memories: List<Memory>): String? {
        if (memories.isEmpty()) return null

        val what = if (memories.size == 1) {
            memories.single().chips.joinToString(" + ") { it.name.lowercase() }
        } else {
            val noun = when (memories.map { it.type }.distinct().singleOrNull()) {
                MemoryType.IMAGE -> "images"
                MemoryType.PDF -> "pdfs"
                else -> "items"
            }
            "${memories.size} $noun"
        }

        // A source is shown only when every item agrees on one.
        val from = memories.map { it.sourceAppLabel ?: it.sourceApp }
            .distinct()
            .singleOrNull()
            ?.let { "from ${it.lowercase()}" }

        return listOfNotNull(what, from).joinToString(" · ")
    }

    fun previewFor(memories: List<Memory>): CapturePreview? {
        if (memories.isEmpty()) return null
        if (memories.size > 1) return CapturePreview.Files(memories)

        val memory = memories.single()
        val text = memory.rawText?.trim()?.takeIf { it.isNotEmpty() }

        return when (memory.type) {
            MemoryType.IMAGE -> CapturePreview.Photo(memory, caption = text)
            MemoryType.PDF -> CapturePreview.Document(memory, title = memory.title, caption = text)
            MemoryType.LINK -> CapturePreview.Link(
                host = UrlText.firstHost(text),
                // A page title reads better than a URL. When there is one, the
                // URL moves down to the detail line instead of disappearing.
                headline = memory.title ?: text.orEmpty(),
                detail = if (memory.title != null) text else null,
            )
            MemoryType.TEXT, MemoryType.AUDIO -> CapturePreview.Note(text ?: memory.title.orEmpty())
        }
    }
}
