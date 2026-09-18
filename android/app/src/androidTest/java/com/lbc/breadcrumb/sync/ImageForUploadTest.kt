package com.lbc.breadcrumb.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real decoder and encoder, on the device: what leaves the phone is what
 * the model is charged for, so its size is worth asserting rather than trusting.
 */
@RunWith(AndroidJUnit4::class)
class ImageForUploadTest {

    private lateinit var sandbox: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sandbox = File(context.cacheDir, "image-for-upload-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        sandbox.deleteRecursively()
    }

    private fun png(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return File(sandbox, "${width}x$height.png").apply {
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun aLargePictureIsShrunkUnderTheCapAndSentAsJpeg() {
        val bytes = ImageForUpload.prepare(png(3_000, 2_000))!!

        // JPEG, whatever came in
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0xD8.toByte(), bytes[1])

        val sent = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sent)
        assertTrue("long edge ${sent.outWidth} is over the cap", maxOf(sent.outWidth, sent.outHeight) <= ImageForUpload.MAX_EDGE)
        assertEquals("the shape must survive shrinking", 1_500, sent.outWidth)
        assertEquals(1_000, sent.outHeight)
    }

    @Test
    fun aSmallPictureKeepsItsSize() {
        val bytes = ImageForUpload.prepare(png(800, 600))!!

        val sent = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sent)
        assertEquals(800, sent.outWidth)
    }

    @Test
    fun aFileThatIsNotAPictureGivesNothingToSend() {
        val notAPicture = File(sandbox, "notes.png").apply { writeText("%PDF-1.7") }

        assertNull(ImageForUpload.prepare(notAPicture))
    }
}
