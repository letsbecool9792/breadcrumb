package com.lbc.breadcrumb.open

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lbc.breadcrumb.R
import com.lbc.breadcrumb.capture.UrlText
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.OriginalStore
import java.io.File

/**
 * What "Open original" does for a memory (step 4.5). Breadcrumb's job ends at
 * handing back the thing that was saved, so every action leaves the app --
 * except a note, whose original is its own text, and which is handed back by
 * copying it.
 */
sealed interface OriginalAction {
    /** A stored picture or PDF, opened in whatever views that kind of file. */
    data class ViewFile(val file: File, val mimeType: String) : OriginalAction

    data class ViewUrl(val url: String) : OriginalAction

    data class CopyText(val text: String) : OriginalAction

    /** Nothing to hand back: the file is gone, or there never was one -- a debug sample, say. */
    data object Missing : OriginalAction
}

object Originals {

    /**
     * Content URIs for stored originals. Must match the provider's authority in
     * AndroidManifest.xml, whose paths expose the originals folder and nothing else.
     */
    fun authority(context: Context): String = "${context.packageName}.originals"

    /** @param fileFor the stored original, or null -- [OriginalStore.fileFor], which refuses anything outside the store. */
    fun actionFor(memory: Memory, fileFor: (Memory) -> File?): OriginalAction = when (memory.type) {
        MemoryType.IMAGE, MemoryType.PDF, MemoryType.AUDIO ->
            fileFor(memory)?.let { OriginalAction.ViewFile(it, mimeType(memory.type, it.extension)) }
                ?: OriginalAction.Missing
        // a link with no URL in it cannot be opened, but its text can still be taken
        MemoryType.LINK -> UrlText.firstUrl(memory.rawText)?.let { OriginalAction.ViewUrl(it) } ?: copyOrMissing(memory)
        MemoryType.TEXT -> copyOrMissing(memory)
    }

    private fun copyOrMissing(memory: Memory): OriginalAction =
        memory.rawText?.takeIf { it.isNotBlank() }?.let { OriginalAction.CopyText(it) } ?: OriginalAction.Missing

    fun mimeType(type: MemoryType, extension: String): String = when (type) {
        MemoryType.PDF -> "application/pdf"
        else -> when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> if (type == MemoryType.IMAGE) "image/*" else "*/*"
        }
    }

    /**
     * An intent another app can open the original with. Originals are
     * app-private, so the file goes as a content URI with a read grant for
     * this one intent -- never a file path, and never a lasting permission.
     */
    fun viewIntent(context: Context, action: OriginalAction.ViewFile): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(context, authority(context), action.file), action.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Carries out [action]. Says so with a toast when nothing on the phone can open it. */
    fun perform(context: Context, action: OriginalAction) {
        when (action) {
            is OriginalAction.ViewFile -> start(context, viewIntent(context, action))
            is OriginalAction.ViewUrl -> start(context, Intent(Intent.ACTION_VIEW, Uri.parse(action.url)))
            is OriginalAction.CopyText -> {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), action.text))
                // Android 13+ confirms a copy itself; a toast on top would say it twice
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, R.string.detail_copied, Toast.LENGTH_SHORT).show()
                }
            }
            OriginalAction.Missing -> Unit
        }
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.detail_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    /** The action for a memory, against the real store. */
    fun actionFor(context: Context, memory: Memory): OriginalAction =
        actionFor(memory, OriginalStore(context)::fileFor)
}
