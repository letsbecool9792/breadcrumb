package com.lbc.breadcrumb.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Shrinks a saved picture to what is worth sending for reading (step 3.6).
 *
 * A model charges by tile, so a full-size screenshot costs several times what
 * a shrunk one does and says no more: it is looking for what the picture
 * shows, and the small print was OCR's job on the device. JPEG, since these
 * are photographs and screenshots rather than line art, and the difference on
 * a 4G connection is real.
 */
object ImageForUpload {

    /** Long edge after shrinking. Two model tiles across, enough to read a headline. */
    const val MAX_EDGE = 1_536

    const val QUALITY = 80

    const val MIME_TYPE = "image/jpeg"

    /** @return the bytes to send, or null when the file is not a picture we can read. */
    fun prepare(file: File, maxEdge: Int = MAX_EDGE): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageRules.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        val bitmap: Bitmap = BitmapFactory.decodeFile(file.path, options) ?: return null

        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            out.toByteArray()
        }
    }
}

/** Kept apart from the Android calls above so the arithmetic is covered by JVM tests. */
object ImageRules {

    /**
     * The power-of-two `inSampleSize` that brings the long edge to [maxEdge] or
     * just under. Halving is all BitmapFactory offers, and it is cheap: it
     * never allocates the full-size bitmap on the way.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > maxEdge) sample *= 2
        return sample
    }
}
