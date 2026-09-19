package com.lbc.breadcrumb.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Reads the text out of a stored PDF, on the device (architecture rule 4), so
 * a PDF is found by what it says rather than only by its filename.
 *
 * Two ways in, cheapest first:
 *
 * - **Its text layer**, on Android 15 and later, where PdfRenderer can give a
 *   page's text (`Page.getTextContents`). Exact and instant, and what almost
 *   every PDF made by a computer carries.
 * - **OCR of its first pages**, rendered to bitmaps, when there is no text
 *   layer to speak of -- a scan, a photo saved as a PDF -- or on an older
 *   Android. The same bundled recognizer as images, so still offline.
 *
 * What comes back feeds the ordinary upload: the server's model reads it with
 * the rest of a batch, so a PDF costs no request of its own.
 *
 * Not safe for concurrent use; [OcrQueue] reads one file at a time.
 */
class PdfReader(private val ocr: MlKitOcrReader) : OcrReader {

    override suspend fun read(file: File): String = withContext(Dispatchers.IO) {
        val descriptor = try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            Log.w(TAG, "could not open ${file.name}", e)
            return@withContext ""
        }
        descriptor.use { fd ->
            // A file PdfRenderer cannot open -- not a PDF, or one behind a
            // password -- will not open next time either: a final answer.
            val renderer = try {
                PdfRenderer(fd)
            } catch (e: IOException) {
                Log.w(TAG, "not a PDF we can open: ${file.name}", e)
                return@withContext ""
            } catch (e: SecurityException) {
                Log.w(TAG, "a PDF behind a password: ${file.name}", e)
                return@withContext ""
            }
            renderer.use { read(it) }
        }
    }

    private suspend fun read(renderer: PdfRenderer): String {
        val layer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val (pages, read) = textLayer(renderer)
            PdfRules.clean(pages).also { if (PdfRules.hasTextLayer(it, read)) return it }
        } else {
            ""
        }
        // thin or missing: read the pages by eye too, and keep whichever says more
        return PdfRules.better(layer, PdfRules.clean(recognized(renderer)))
    }

    /** Each page's own text, until enough is gathered. Returns the pages' text and how many were read. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun textLayer(renderer: PdfRenderer): Pair<List<String>, Int> {
        val pages = mutableListOf<String>()
        var length = 0
        val count = renderer.pageCount.coerceAtMost(PdfRules.MAX_TEXT_PAGES)
        for (index in 0 until count) {
            val text = renderer.openPage(index).use { page ->
                page.textContents.joinToString("\n") { it.text }
            }
            pages += text
            length += text.length
            if (length >= PdfRules.MAX_CHARS) break
        }
        return pages to pages.size
    }

    /** The first few pages rendered white-backed and read by OCR. */
    private suspend fun recognized(renderer: PdfRenderer): List<String> {
        val pages = mutableListOf<String>()
        val count = renderer.pageCount.coerceAtMost(PdfRules.MAX_OCR_PAGES)
        for (index in 0 until count) {
            val bitmap = renderer.openPage(index).use { page ->
                val (width, height) = PdfRules.renderSize(page.width, page.height)
                if (width == 0) return@use null
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    // pages render onto transparency, and the recognizer reads black on white
                    it.eraseColor(Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } ?: continue
            try {
                pages += ocr.read(bitmap)
            } finally {
                bitmap.recycle()
            }
        }
        return pages
    }

    override fun release() = ocr.release()

    private companion object {
        const val TAG = "PdfReader"
    }
}
