package com.lbc.breadcrumb.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real recognizer, on the device, against images drawn by the test -- so
 * the bundled model, decoding and the Task bridge are all exercised without
 * depending on any file that happens to be on the phone.
 */
@RunWith(AndroidJUnit4::class)
class MlKitOcrReaderTest {

    private lateinit var sandbox: File
    private val reader = MlKitOcrReader()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sandbox = File(context.cacheDir, "ocr-reader-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        reader.release()
        sandbox.deleteRecursively()
    }

    /** A screenshot-sized PNG with [lines] drawn black on white. */
    private fun screenshot(vararg lines: String, name: String = "shot.png"): File {
        val bitmap = Bitmap.createBitmap(1080, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 56f
            typeface = Typeface.SANS_SERIF
        }
        lines.forEachIndexed { i, line -> canvas.drawText(line, 60f, 160f + i * 110f, paint) }
        return File(sandbox, name).apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }

    @Test
    fun readsTheTextInAScreenshot() = runBlocking {
        val text = reader.read(screenshot("Qualcomm Software Intern", "Apply by April 30"))

        assertTrue("got: $text", text.contains("Qualcomm", ignoreCase = true))
        assertTrue("got: $text", text.contains("April", ignoreCase = true))
    }

    @Test
    fun readsAgainAfterRelease() = runBlocking {
        reader.read(screenshot("First read", name = "a.png"))
        reader.release()

        val text = reader.read(screenshot("Second read", name = "b.png"))

        assertTrue("got: $text", text.contains("Second", ignoreCase = true))
    }

    @Test
    fun aBlankImageHasNoText() = runBlocking {
        assertEquals("", reader.read(screenshot()))
    }

    @Test
    fun aFileThatIsNotAnImageHasNoText() = runBlocking {
        val notAnImage = File(sandbox, "notes.png").apply { writeText("%PDF-1.7 not really a picture") }

        assertEquals("", reader.read(notAnImage))
    }
}
