package com.lbc.breadcrumb.ui.debug

import android.content.Context
import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.OriginalStore
import java.io.File

/** What the debug list shows for a memory's stored file. */
data class OriginalPreview(val thumbnail: ImageBitmap?, val label: String)

/**
 * Decodes a small thumbnail straight from disk. Deliberately no image-loading
 * library: this screen is thrown away at 4.2, and the real UI will want
 * caching and placeholders that belong in that decision, not this one.
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
