package com.lbc.breadcrumb.ui.common

import android.content.Context
import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.OriginalStore
import java.io.File

/** A memory's stored file, as small UI surfaces show it: a thumbnail and a type/size label. */
data class OriginalPreview(val thumbnail: ImageBitmap?, val label: String)

/**
 * Decodes a small thumbnail straight from disk. Used by the debug list and the
 * capture sheet. Deliberately no image-loading library yet: the search UI at
 * 4.2 will want caching and placeholders, and that decision belongs there.
 *
 * Call off the main thread.
 */
fun loadOriginalPreview(context: Context, memory: Memory, targetPx: Int = 320): OriginalPreview? {
    val file = OriginalStore(context).fileFor(memory) ?: return null
    val label = "${file.extension} · ${Formatter.formatShortFileSize(context, file.length())}"
    return OriginalPreview(thumbnail = decodeSampled(file, targetPx), label = label)
}

private fun decodeSampled(file: File, targetPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null  // a PDF, or not an image

    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
        sample *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
}
