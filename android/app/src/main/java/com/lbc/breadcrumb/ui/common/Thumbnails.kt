package com.lbc.breadcrumb.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryType
import com.lbc.breadcrumb.data.OriginalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pictures of saved originals for the search screens: a photo scaled down, or
 * a PDF's first page drawn.
 *
 * A small in-memory cache and no image-loading library. Everything shown is a
 * local file in one folder, so a library's network stack, disk cache and
 * request pipeline would all go unused; what the mosaic needs is only that
 * scrolling back does not decode the same file again.
 */
object Thumbnails {

    /** An eighth of the heap, counted in kilobytes: Android's own advice for a bitmap cache. */
    private val cache = object : LruCache<String, ImageBitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4 / 1024
    }

    private fun key(memory: Memory, targetPx: Int) = "${memory.id}@$targetPx:${memory.localUri}"

    fun cached(memory: Memory, targetPx: Int): ImageBitmap? = cache.get(key(memory, targetPx))

    /**
     * Decodes on the calling thread; call off the main one. Null when there is
     * no stored original, or it cannot be drawn -- an encrypted PDF, a
     * truncated file.
     */
    fun load(context: Context, memory: Memory, targetPx: Int): ImageBitmap? {
        cached(memory, targetPx)?.let { return it }
        val file = OriginalStore(context).fileFor(memory) ?: return null
        val bitmap = runCatching {
            if (memory.type == MemoryType.PDF) firstPage(file, targetPx) else sampled(file, targetPx)
        }.getOrNull() ?: return null
        return bitmap.asImageBitmap().also { cache.put(key(memory, targetPx), it) }
    }

    /** The largest power-of-two reduction that keeps the short side at or above [targetPx]. */
    private fun sampled(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Page one, [targetPx] wide, on white -- a PDF page has no background of its own. */
    private fun firstPage(file: File, targetPx: Int): Bitmap? {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) return null
                renderer.openPage(0).use { page ->
                    val height = (targetPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    return Bitmap.createBitmap(targetPx, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }
}

/**
 * A memory's picture at about [targetPx], from the cache at once when it is
 * there -- so a tile scrolled back into view does not blink -- and decoded in
 * the background when it is not.
 */
@Composable
fun rememberThumbnail(memory: Memory, targetPx: Int): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState(Thumbnails.cached(memory, targetPx), memory.id, memory.localUri, targetPx) {
        if (value == null && memory.localUri != null) {
            value = withContext(Dispatchers.IO) { Thumbnails.load(context, memory, targetPx) }
        }
    }
    return bitmap
}
