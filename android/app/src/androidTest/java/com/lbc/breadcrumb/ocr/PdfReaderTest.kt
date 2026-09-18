package com.lbc.breadcrumb.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real reader, on the device, against PDFs the test draws -- one with a
 * text layer, one that is only a picture of text, the way a scan is.
 */
@RunWith(AndroidJUnit4::class)
class PdfReaderTest {

    private lateinit var sandbox: File
    private val reader = PdfReader(MlKitOcrReader())

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sandbox = File(context.cacheDir, "pdf-reader-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        reader.release()
        sandbox.deleteRecursively()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 22f
        typeface = Typeface.SANS_SERIF
    }

    /** An A4 PDF whose pages carry [pages] as real text. */
    private fun textPdf(vararg pages: List<String>, name: String = "notes.pdf"): File {
        val document = PdfDocument()
        pages.forEachIndexed { index, lines ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
            lines.forEachIndexed { i, line -> page.canvas.drawText(line, 50f, 80f + i * 34f, paint) }
            document.finishPage(page)
        }
        return File(sandbox, name).apply { outputStream().use { document.writeTo(it) } }.also { document.close() }
    }

    /** An A4 PDF holding only a picture of [lines], as a scanner makes one. */
    private fun scannedPdf(vararg lines: String): File {
        val picture = Bitmap.createBitmap(1190, 1684, Bitmap.Config.ARGB_8888)
        Canvas(picture).apply {
            drawColor(Color.WHITE)
            val big = Paint(paint).apply { textSize = 64f }
            lines.forEachIndexed { i, line -> drawText(line, 100f, 200f + i * 120f, big) }
        }
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawBitmap(picture, null, android.graphics.Rect(0, 0, 595, 842), null)
        document.finishPage(page)
        return File(sandbox, "scan.pdf").apply { outputStream().use { document.writeTo(it) } }.also {
            document.close()
            picture.recycle()
        }
    }

    @Test
    fun readsAPdfsText() = runBlocking {
        val text = reader.read(
            textPdf(
                listOf("Data Structures, week 6", "Balanced trees: AVL rotations"),
                listOf("Red-black invariants and the Qualcomm interview"),
            )
        )

        assertTrue("got: $text", text.contains("AVL rotations"))
        // the second page too, not just the first
        assertTrue("got: $text", text.contains("Qualcomm"))
    }

    @Test
    fun theTextLayerIsReadExactlyOnAndroid15AndLater() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)

        val text = reader.read(textPdf(listOf("Invoice 4471-B for ACME Widgets, due 30 April")))

        // exact, character for character -- OCR would be close, not exact
        assertEquals("Invoice 4471-B for ACME Widgets, due 30 April", text)
    }

    @Test
    fun readsAScanByOcr() = runBlocking {
        val text = reader.read(scannedPdf("Lease agreement", "Flat 4B Koramangala"))

        assertTrue("got: $text", text.contains("Lease", ignoreCase = true))
        assertTrue("got: $text", text.contains("Koramangala", ignoreCase = true))
    }

    @Test
    fun aFileThatIsNotAPdfHasNoText() = runBlocking {
        val notAPdf = File(sandbox, "notes.pdf").apply { writeText("not a PDF at all") }

        assertEquals("", reader.read(notAPdf))
    }

    @Test
    fun aLongDocumentIsCapped() = runBlocking {
        // before Android 15 only the first pages are read, by OCR, and never reach the cap
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
        val line = "Chapter text that goes on and on about balanced search trees"
        val pages = Array(40) { List(22) { line } }

        val text = reader.read(textPdf(*pages, name = "book.pdf"))

        assertTrue("${text.length} characters", text.length <= PdfRules.MAX_CHARS)
        assertTrue(text.length > PdfRules.MAX_CHARS / 2)
    }
}
